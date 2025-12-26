#!/usr/bin/env python3

import subprocess
import sys
import os
import re
import logging
from pathlib import Path
import shutil
import json
from PIL import Image
import tempfile

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
        'ffprobe',
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

def detect_file_type(file_path, format_name):
    """
    Detect file type: 'image', 'video', 'audio', or 'document'.
    Returns tuple: (file_type, file_category)
    """
    extension = Path(file_path).suffix.lower()

    # Image extensions
    image_extensions = {'.jpg', '.jpeg', '.png', '.gif', '.bmp', '.tiff', '.tif',
                        '.webp', '.heic', '.heif', '.ico', '.svg'}
    image_formats = {'jpeg', 'jpg', 'png', 'gif', 'bmp', 'tiff', 'webp', 'heic',
                     'heif', 'image2', 'svg'}

    # Video extensions
    video_extensions = {'.mp4', '.avi', '.mov', '.mkv', '.flv', '.wmv', '.webm',
                        '.m4v', '.mpg', '.mpeg', '.3gp', '.ogv', '.mts', '.m2ts'}
    video_formats = {'mov', 'mp4', 'avi', 'mkv', 'flv', 'wmv', 'webm', 'matroska',
                     'mpeg', 'mpegts', 'ogg'}

    # Audio extensions
    audio_extensions = {'.mp3', '.wav', '.flac', '.aac', '.ogg', '.wma', '.m4a',
                        '.opus', '.oga', '.aiff', '.ape'}
    audio_formats = {'mp3', 'wav', 'flac', 'aac', 'ogg', 'wma', 'oga', 'opus',
                     'aiff', 'ape'}

    # Document extensions
    document_extensions = {'.pdf', '.doc', '.docx', '.xls', '.xlsx', '.ppt', '.pptx',
                           '.txt', '.rtf', '.odt', '.ods', '.odp', '.epub'}

    format_lower = format_name.lower()

    # Check for image
    if extension in image_extensions or any(fmt in format_lower for fmt in image_formats):
        logger.info(f"Detected as IMAGE: {extension}")
        return 'image', extension

    # Check for video
    if extension in video_extensions or any(fmt in format_lower for fmt in video_formats):
        logger.info(f"Detected as VIDEO: {extension}")
        return 'video', extension

    # Check for audio
    if extension in audio_extensions or any(fmt in format_lower for fmt in audio_formats):
        logger.info(f"Detected as AUDIO: {extension}")
        return 'audio', extension

    # Check for document
    if extension in document_extensions:
        logger.info(f"Detected as DOCUMENT: {extension}")
        return 'document', extension

    # Default to video/audio processing if has duration
    logger.warning(f"Unknown file type for {extension}, attempting media compression")
    return 'unknown', extension

def compress_image(input_path, output_path, target_size_bytes):
    """Compress an image to approximate the target size using Pillow."""
    input_size = get_file_size(input_path)
    logger.info(f"Input image size: {input_size} bytes, target size: {target_size_bytes} bytes")

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

    try:
        with Image.open(input_path) as img:
            # Convert to RGB if necessary
            if img.mode not in ('RGB', 'L'):
                img = img.convert('RGB')

            temp_output = output_path + '.temp.jpg'

            # If input size is already smaller than target
            if input_size <= target_size_bytes:
                logger.info("Input size already meets target; saving as JPEG")
                img.save(output_path, 'JPEG', quality=85, optimize=True)
                return

            # Binary search for JPEG quality
            min_quality = 5
            max_quality = 95
            tolerance = 0.15  # Allow ±15% of target size
            max_attempts = 10

            for attempt in range(max_attempts):
                quality = (min_quality + max_quality) // 2
                logger.debug(f"Attempt {attempt + 1}, quality: {quality}")
                img.save(temp_output, 'JPEG', quality=quality, optimize=True)
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

            # Use last attempt if reasonably close
            if Path(temp_output).exists():
                current_size = get_file_size(temp_output)
                if current_size <= target_size_bytes * 1.3:  # Within 30%
                    os.rename(temp_output, output_path)
                    logger.warning(f"Image compression close to target, final size: {current_size} bytes")
                    return
                os.remove(temp_output)

            raise RuntimeError(f"Could not compress image to target size (final: {current_size} bytes, target: {target_size_bytes} bytes)")

    except Exception as e:
        logger.error(f"Image compression failed: {str(e)}")
        sys.stderr.write(f"Image compression failed: {str(e)}\n")
        raise

def compress_video(input_path, output_path, target_size_bytes, duration):
    """Compress a video to approximate the target size using two-pass encoding."""
    input_size = get_file_size(input_path)
    logger.info(f"Input video size: {input_size} bytes, target size: {target_size_bytes} bytes, duration: {duration}s")

    if input_size <= target_size_bytes:
        logger.info("Input size already meets target; copying input file")
        shutil.copy(input_path, output_path)
        return

    audio_bitrate_kbps = 128
    tolerance = 0.15  # 15% tolerance
    max_attempts = 5
    min_bitrate_kbps = 50
    max_bitrate_kbps = 5000

    # Calculate target bitrate (90% for video, 10% for audio overhead)
    target_bitrate_kbps = (target_size_bytes * 8 * 0.9) / (duration * 1000)
    video_bitrate_kbps = max(min_bitrate_kbps, target_bitrate_kbps - audio_bitrate_kbps)

    temp_output = output_path + '.temp.mp4'
    output_dir = Path(temp_output).parent

    try:
        output_dir.mkdir(parents=True, exist_ok=True)
        if not os.access(output_dir, os.W_OK):
            raise PermissionError(f"Output directory {output_dir} is not writable")
    except Exception as e:
        logger.error(f"Failed to create output directory: {str(e)}")
        sys.stderr.write(f"Failed to create output directory: {str(e)}\n")
        raise

    for attempt in range(max_attempts):
        logger.debug(f"Attempt {attempt + 1}, video bitrate: {video_bitrate_kbps}kbps")

        cmd_pass1 = [
            'ffmpeg', '-i', input_path,
            '-c:v', 'libx264', '-b:v', f'{int(video_bitrate_kbps)}k',
            '-vf', 'scale=trunc(iw/2)*2:trunc(ih/2)*2',
            '-pass', '1', '-an', '-f', 'null', '-y', '/dev/null'
        ]

        cmd_pass2 = [
            'ffmpeg', '-i', input_path,
            '-c:v', 'libx264', '-b:v', f'{int(video_bitrate_kbps)}k',
            '-vf', 'scale=trunc(iw/2)*2:trunc(ih/2)*2',
            '-pass', '2', '-c:a', 'aac', '-b:a', f'{audio_bitrate_kbps}k',
            '-y', temp_output
        ]

        try:
            logger.debug(f"Running FFmpeg pass 1")
            subprocess.run(cmd_pass1, capture_output=True, text=True, check=True)
            logger.debug(f"Running FFmpeg pass 2")
            subprocess.run(cmd_pass2, capture_output=True, text=True, check=True)

            current_size = get_file_size(temp_output)
            logger.debug(f"Video compressed, size: {current_size} bytes")

            if abs(current_size - target_size_bytes) <= target_size_bytes * tolerance:
                os.rename(temp_output, output_path)
                logger.info(f"Video compression successful, final size: {current_size} bytes")
                return
            elif current_size > target_size_bytes:
                max_bitrate_kbps = video_bitrate_kbps
                video_bitrate_kbps = (video_bitrate_kbps + min_bitrate_kbps) / 2
            else:
                min_bitrate_kbps = video_bitrate_kbps
                video_bitrate_kbps = (video_bitrate_kbps + max_bitrate_kbps) / 2

            if abs(max_bitrate_kbps - min_bitrate_kbps) < 10:
                break

        except subprocess.CalledProcessError as e:
            logger.error(f"FFmpeg failed: {e.stderr}")
            raise RuntimeError(f"Video compression failed: {e.stderr}")
        finally:
            if Path(temp_output).exists() and attempt < max_attempts - 1:
                os.remove(temp_output)
            # Clean up ffmpeg logs
            for log_file in ['ffmpeg2pass-0.log', 'ffmpeg2pass-0.log.mbtree']:
                if Path(log_file).exists():
                    os.remove(log_file)

    # Use last attempt if within reasonable range
    if Path(temp_output).exists():
        current_size = get_file_size(temp_output)
        if current_size <= target_size_bytes * 1.3:  # Within 30%
            os.rename(temp_output, output_path)
            logger.warning(f"Video compression close to target, final size: {current_size} bytes")
            return
        os.remove(temp_output)

    raise RuntimeError(f"Could not compress video to target size (final: {current_size} bytes, target: {target_size_bytes} bytes)")

def compress_audio(input_path, output_path, target_size_bytes, duration):
    """Compress audio to approximate the target size."""
    input_size = get_file_size(input_path)
    logger.info(f"Input audio size: {input_size} bytes, target size: {target_size_bytes} bytes, duration: {duration}s")

    if input_size <= target_size_bytes:
        logger.info("Input size already meets target; copying input file")
        shutil.copy(input_path, output_path)
        return

    tolerance = 0.15
    max_attempts = 5
    min_bitrate_kbps = 32
    max_bitrate_kbps = 320

    # Calculate target bitrate
    target_bitrate_kbps = (target_size_bytes * 8) / (duration * 1000)
    audio_bitrate_kbps = max(min_bitrate_kbps, min(target_bitrate_kbps, max_bitrate_kbps))

    temp_output = output_path + '.temp.mp3'
    output_dir = Path(temp_output).parent

    try:
        output_dir.mkdir(parents=True, exist_ok=True)
    except Exception as e:
        logger.error(f"Failed to create output directory: {str(e)}")
        raise

    for attempt in range(max_attempts):
        logger.debug(f"Attempt {attempt + 1}, audio bitrate: {audio_bitrate_kbps}kbps")

        cmd = [
            'ffmpeg', '-i', input_path,
            '-vn',  # No video
            '-c:a', 'libmp3lame',
            '-b:a', f'{int(audio_bitrate_kbps)}k',
            '-y', temp_output
        ]

        try:
            subprocess.run(cmd, capture_output=True, text=True, check=True)
            current_size = get_file_size(temp_output)
            logger.debug(f"Audio compressed, size: {current_size} bytes")

            if abs(current_size - target_size_bytes) <= target_size_bytes * tolerance:
                os.rename(temp_output, output_path)
                logger.info(f"Audio compression successful, final size: {current_size} bytes")
                return
            elif current_size > target_size_bytes:
                max_bitrate_kbps = audio_bitrate_kbps
                audio_bitrate_kbps = (audio_bitrate_kbps + min_bitrate_kbps) / 2
            else:
                min_bitrate_kbps = audio_bitrate_kbps
                audio_bitrate_kbps = (audio_bitrate_kbps + max_bitrate_kbps) / 2

            if abs(max_bitrate_kbps - min_bitrate_kbps) < 5:
                break

        except subprocess.CalledProcessError as e:
            logger.error(f"Audio compression failed: {e.stderr}")
            raise RuntimeError(f"Audio compression failed: {e.stderr}")
        finally:
            if Path(temp_output).exists() and attempt < max_attempts - 1:
                os.remove(temp_output)

    if Path(temp_output).exists():
        current_size = get_file_size(temp_output)
        if current_size <= target_size_bytes * 1.3:
            os.rename(temp_output, output_path)
            logger.warning(f"Audio compression close to target, final size: {current_size} bytes")
            return
        os.remove(temp_output)

    raise RuntimeError(f"Could not compress audio to target size")

def compress_document(input_path, output_path, target_size_bytes):
    """Compress documents (PDF, Office files) using various methods."""
    input_size = get_file_size(input_path)
    extension = Path(input_path).suffix.lower()
    logger.info(f"Input document size: {input_size} bytes, target size: {target_size_bytes} bytes")

    if input_size <= target_size_bytes:
        logger.info("Document already meets target size")
        shutil.copy(input_path, output_path)
        return

    output_dir = Path(output_path).parent
    output_dir.mkdir(parents=True, exist_ok=True)

    # For PDF files
    if extension == '.pdf':
        try:
            # Try using Ghostscript for PDF compression
            cmd = [
                'gs', '-sDEVICE=pdfwrite', '-dCompatibilityLevel=1.4',
                '-dPDFSETTINGS=/ebook',  # /screen, /ebook, /printer, /prepress
                '-dNOPAUSE', '-dQUIET', '-dBATCH',
                f'-sOutputFile={output_path}', input_path
            ]
            subprocess.run(cmd, capture_output=True, text=True, check=True)

            compressed_size = get_file_size(output_path)
            logger.info(f"PDF compressed to {compressed_size} bytes")

            if compressed_size > target_size_bytes * 1.3:
                logger.warning(f"PDF compression did not reach target (compressed to {compressed_size} bytes)")
            return

        except (subprocess.CalledProcessError, FileNotFoundError) as e:
            logger.warning(f"Ghostscript compression failed: {str(e)}, trying alternative")
            # Fallback: just copy the file
            shutil.copy(input_path, output_path)
            return

    # For Office files (Word, Excel, PowerPoint) - convert to PDF and compress
    elif extension in ['.doc', '.docx', '.xls', '.xlsx', '.ppt', '.pptx']:
        logger.warning(f"Office file compression limited - copying original")
        shutil.copy(input_path, output_path)
        return

    # For text files - simple copy (already small)
    elif extension in ['.txt', '.rtf']:
        shutil.copy(input_path, output_path)
        return

    else:
        logger.warning(f"Unknown document type {extension}, copying original")
        shutil.copy(input_path, output_path)
        return

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

        # Get media info and detect file type
        duration, format_name = get_media_info(input_path)
        file_type, extension = detect_file_type(input_path, format_name)

        # Route to appropriate compression method
        if file_type == 'image':
            compress_image(input_path, output_path, target_size_bytes)
        elif file_type == 'video':
            if duration <= 0:
                logger.error("Invalid video duration")
                sys.stderr.write("Invalid video duration\n")
                sys.exit(1)
            compress_video(input_path, output_path, target_size_bytes, duration)
        elif file_type == 'audio':
            if duration <= 0:
                logger.error("Invalid audio duration")
                sys.stderr.write("Invalid audio duration\n")
                sys.exit(1)
            compress_audio(input_path, output_path, target_size_bytes, duration)
        elif file_type == 'document':
            compress_document(input_path, output_path, target_size_bytes)
        else:
            # Unknown type - try video compression as fallback
            logger.warning("Unknown file type, attempting video compression")
            if duration > 0:
                compress_video(input_path, output_path, target_size_bytes, duration)
            else:
                raise RuntimeError(f"Unable to compress unknown file type: {extension}")

        final_size = get_file_size(output_path)
        logger.info(f"Compression completed successfully, output: {output_path}, final size: {final_size} bytes")

    except Exception as e:
        logger.error(f"Compression failed: {str(e)}")
        sys.stderr.write(f"Compression failed: {str(e)}\n")
        sys.exit(1)

if __name__ == "__main__":
    main()