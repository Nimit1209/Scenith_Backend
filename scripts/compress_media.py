#!/usr/bin/env python3

import subprocess
import sys
import os
import re
import logging
from pathlib import Path
import math
import shutil
import json
from PIL import Image

# Configure logging
logging.basicConfig(level=logging.DEBUG, format='%(asctime)s - %(levelname)s - %(message)s')
logger = logging.getLogger(__name__)

def get_file_size(file_path):
    """Get the size of a file in bytes."""
    try:
        return Path(file_path).stat().st_size
    except Exception as e:
        logger.error(f"Failed to get file size for {file_path}: {str(e)}")
        raise

def parse_target_size(target_size_str):
    """Parse target size string (e.g., '500KB' or '2MB') to bytes."""
    match = re.match(r'^(\d+)(KB|MB)$', target_size_str, re.IGNORECASE)
    if not match:
        logger.error(f"Invalid target size format: {target_size_str}")
        sys.stderr.write(f"Invalid target size format: {target_size_str}\n")
        raise ValueError(f"Invalid target size format: {target_size_str}")

    size, unit = int(match.group(1)), match.group(2).upper()
    return size * (1024 if unit == 'KB' else 1024 * 1024)

def get_media_info(input_path):
    """Get media duration and format using ffprobe."""
    cmd = [
        '/usr/local/bin/ffprobe',
        '-v', 'error',
        '-show_entries', 'format=duration,format_name',
        '-of', 'json',
        input_path
    ]
    try:
        result = subprocess.run(cmd, capture_output=True, text=True, check=True)
        info = json.loads(result.stdout)
        duration = float(info.get('format', {}).get('duration', 0))
        format_name = info.get('format', {}).get('format_name', '')
        logger.debug(f"Media info for {input_path}: duration={duration}, format_name={format_name}")
        return duration, format_name
    except subprocess.CalledProcessError as e:
        logger.error(f"Failed to get media info for {input_path}: {e.stderr}")
        sys.stderr.write(f"Failed to get media info: {e.stderr}\n")
        raise
    except Exception as e:
        logger.error(f"Error parsing media info for {input_path}: {str(e)}")
        sys.stderr.write(f"Error parsing media info: {str(e)}\n")
        raise

def is_image(file_path, format_name):
    """Check if the file is an image based on extension and format."""
    image_extensions = {'.jpg', '.jpeg', '.png', '.heic', '.heif'}
    image_formats = {'jpeg', 'jpg', 'png', 'heic', 'heif', 'image2'}

    extension = Path(file_path).suffix.lower()
    if extension not in image_extensions:
        logger.error(f"Unsupported file extension: {extension}")
        sys.stderr.write(f"Unsupported file extension: {extension}\n")
        return False

    format_lower = format_name.lower()
    if not any(fmt in format_lower for fmt in image_formats):
        logger.error(f"Unsupported file format: {format_name}")
        sys.stderr.write(f"Unsupported file format: {format_name}\n")
        return False

    return True

def compress_image(input_path, output_path, target_size_bytes):
    """Compress an image to approximate the target size using Pillow."""
    input_size = get_file_size(input_path)
    logger.info(f"Input image size: {input_size} bytes, target size: {target_size_bytes} bytes")

    # Ensure output directory is writable
    output_dir = Path(output_path).parent
    try:
        output_dir.mkdir(parents=True, exist_ok=True)
        if not os.access(output_dir, os.W_OK):
            logger.error(f"Output directory {output_dir} is not writable")
            sys.stderr.write(f"Output directory {output_dir} is not writable\n")
            raise PermissionError(f"Output directory {output_dir} is not writable")
    except Exception as e:
        logger.error(f"Failed to create or access output directory {output_dir}: {str(e)}")
        sys.stderr.write(f"Failed to create or access output directory: {str(e)}\n")
        raise

    # Open and convert image to JPEG
    try:
        with Image.open(input_path) as img:
            # Convert to RGB if necessary (e.g., for PNG with transparency or HEIC)
            if img.mode != 'RGB':
                img = img.convert('RGB')
            temp_output = output_path + '.temp.jpg'

            # If input size is close to target, save directly
            if input_size <= target_size_bytes * 1.1:
                logger.info("Input size is smaller or close to target; saving as JPEG")
                img.save(output_path, 'JPEG', quality=85)
                return

            # Binary search for JPEG quality
            min_quality = 5
            max_quality = 95
            tolerance = 0.1  # Allow ±10% of target size
            max_attempts = 10

            for attempt in range(max_attempts):
                quality = (min_quality + max_quality) // 2
                logger.debug(f"Attempt {attempt + 1}, quality: {quality}")
                img.save(temp_output, 'JPEG', quality=quality)
                current_size = get_file_size(temp_output)
                logger.debug(f"Compressed size: {current_size} bytes")

                if abs(current_size - target_size_bytes) <= target_size_bytes * tolerance:
                    os.rename(temp_output, output_path)
                    logger.info(f"Image compression successful, final size: {current_size} bytes")
                    return
                elif current_size > target_size_bytes:
                    max_quality = quality - 1
                else:
                    min_quality = quality + 1

                if max_quality <= min_quality:
                    break

                if Path(temp_output).exists():
                    os.remove(temp_output)

            # Use last attempt if within tolerance
            if Path(temp_output).exists():
                current_size = get_file_size(temp_output)
                if abs(current_size - target_size_bytes) <= target_size_bytes * tolerance * 1.5:
                    os.rename(temp_output, output_path)
                    logger.warning(f"Image compression close enough, final size: {current_size} bytes")
                    return

            raise RuntimeError(f"Could not compress image to target size (final size: {current_size} bytes)")

    except Exception as e:
        logger.error(f"Image compression failed: {str(e)}")
        sys.stderr.write(f"Image compression failed: {str(e)}\n")
        raise

def compress_video(input_path, output_path, target_size_bytes, duration):
    """Compress a video to approximate the target size using two-pass encoding."""
    input_size = get_file_size(input_path)
    logger.info(f"Input video size: {input_size} bytes, target size: {target_size_bytes} bytes, duration: {duration}s")

    if input_size <= target_size_bytes * 1.1:
        logger.info("Input size is smaller or close to target; copying input file")
        shutil.copy(input_path, output_path)
        return

    audio_bitrate_kbps = 128
    tolerance = 0.1
    max_attempts = 5
    min_bitrate_kbps = 100
    max_bitrate_kbps = 5000
    target_bitrate_kbps = (target_size_bytes * 8 * 0.9) / (duration * 1000)
    video_bitrate_kbps = max(min_bitrate_kbps, target_bitrate_kbps - audio_bitrate_kbps)

    temp_output = output_path + '.temp.mp4'

    output_dir = Path(temp_output).parent
    try:
        output_dir.mkdir(parents=True, exist_ok=True)
        if not os.access(output_dir, os.W_OK):
            logger.error(f"Output directory {output_dir} is not writable")
            sys.stderr.write(f"Output directory {output_dir} is not writable\n")
            raise PermissionError(f"Output directory {output_dir} is not writable")
    except Exception as e:
        logger.error(f"Failed to create or access output directory {output_dir}: {str(e)}")
        sys.stderr.write(f"Failed to create or access output directory: {str(e)}\n")
        raise

    for attempt in range(max_attempts):
        logger.debug(f"Attempt {attempt + 1}, video bitrate: {video_bitrate_kbps}kbps")
        cmd_pass1 = [
            'ffmpeg',
            '-i', input_path,
            '-c:v', 'libx264',
            '-b:v', f'{int(video_bitrate_kbps)}k',
            '-vf', 'scale=ceil(iw/2)*2:ih',
            '-pass', '1',
            '-an',
            '-f', 'null',
            '-y', '/dev/null'
        ]
        cmd_pass2 = [
            'ffmpeg',
            '-i', input_path,
            '-c:v', 'libx264',
            '-b:v', f'{int(video_bitrate_kbps)}k',
            '-vf', 'scale=ceil(iw/2)*2:ih',
            '-pass', '2',
            '-c:a', 'aac',
            '-b:a', f'{audio_bitrate_kbps}k',
            '-y', temp_output
        ]

        try:
            logger.debug(f"Running FFmpeg first pass: {' '.join(cmd_pass1)}")
            result = subprocess.run(cmd_pass1, capture_output=True, text=True, check=True)
            logger.debug(f"First pass stdout: {result.stdout}")
            logger.debug(f"Running FFmpeg second pass: {' '.join(cmd_pass2)}")
            result = subprocess.run(cmd_pass2, capture_output=True, text=True, check=True)
            logger.debug(f"Second pass stdout: {result.stdout}")

            current_size = get_file_size(temp_output)
            logger.debug(f"Video compressed, size: {current_size} bytes")

            if abs(current_size - target_size_bytes) <= target_size_bytes * tolerance:
                os.rename(temp_output, output_path)
                logger.info(f"Video compression successful, final size: {current_size} bytes")
                return
            elif current_size > target_size_bytes:
                max_bitrate_kbps = video_bitrate_kbps - 50
                video_bitrate_kbps = (video_bitrate_kbps + min_bitrate_kbps) / 2
            else:
                min_bitrate_kbps = video_bitrate_kbps + 50
                video_bitrate_kbps = (video_bitrate_kbps + max_bitrate_kbps) / 2

            if max_bitrate_kbps <= min_bitrate_kbps:
                break

        except subprocess.CalledProcessError as e:
            logger.error(f"Video compression failed: {e.stderr}")
            sys.stderr.write(f"Video compression failed: {e.stderr}\n")
            raise RuntimeError(f"Video compression failed: {e.stderr}")
        finally:
            if Path(temp_output).exists():
                os.remove(temp_output)
            for log_file in ['ffmpeg2pass-0.log', 'ffmpeg2pass-0.log.mbtree']:
                if Path(log_file).exists():
                    os.remove(log_file)

    if Path(temp_output).exists():
        current_size = get_file_size(temp_output)
        if abs(current_size - target_size_bytes) <= target_size_bytes * tolerance * 1.5:
            os.rename(temp_output, output_path)
            logger.warning(f"Video compression close enough, final size: {current_size} bytes")
            return

    raise RuntimeError(f"Could not compress video to target size (final size: {current_size} bytes)")

def main():
    if len(sys.argv) != 4:
        logger.error("Usage: compress_media.py <input_path> <output_path> <target_size>")
        sys.stderr.write("Usage: compress_media.py <input_path> <output_path> <target_size>\n")
        sys.exit(1)

    input_path, output_path, target_size_str = sys.argv[1:4]

    try:
        target_size_bytes = parse_target_size(target_size_str)
        logger.info(f"Starting compression for {input_path}, target size: {target_size_bytes} bytes")

        if not Path(input_path).exists():
            logger.error(f"Input file does not exist: {input_path}")
            sys.stderr.write(f"Input file does not exist: {input_path}\n")
            sys.exit(1)

        duration, format_name = get_media_info(input_path)
        if is_image(input_path, format_name):
            compress_image(input_path, output_path, target_size_bytes)
        else:
            if duration <= 0:
                logger.error("Invalid video duration")
                sys.stderr.write("Invalid video duration\n")
                sys.exit(1)
            compress_video(input_path, output_path, target_size_bytes, duration)

        final_size = get_file_size(output_path)
        logger.info(f"Compression completed successfully, output: {output_path}, final size: {final_size} bytes")
    except Exception as e:
        logger.error(f"Compression failed: {str(e)}")
        sys.stderr.write(f"Compression failed: {str(e)}\n")
        sys.exit(1)

if __name__ == "__main__":
    main()