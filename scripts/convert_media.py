#!/usr/bin/env python3
import subprocess
import sys
import os
import logging
from pathlib import Path

# Configure logging
logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(levelname)s - %(message)s')
logger = logging.getLogger(__name__)

def convert_video(input_path, output_path, target_format):
    """Convert video using FFmpeg with server paths - FIXED version."""
    try:
        target_format = target_format.lower()

        # Ensure correct extension using os.path.splitext like local version
        base_output_path = os.path.splitext(output_path)[0]
        output_path = f"{base_output_path}.{target_format}"

        # Use server FFmpeg path
        ffmpeg_path = '/usr/local/bin/ffmpeg'

        # Build command properly - separate flags correctly
        command = [
            ffmpeg_path,
            '-i', input_path,
            '-c:v', 'copy',  # Copy video stream to avoid re-encoding (like local)
            '-c:a', 'copy',  # Copy audio stream (like local)
            '-y'  # Overwrite output - MUST be separate flag
        ]

        # Add format-specific flags AFTER basic flags
        if target_format == 'mp4':
            command.extend(['-movflags', '+faststart'])
        elif target_format == 'mkv':
            command.extend(['-c:v', 'copy', '-c:a', 'copy'])

        command.append(output_path)  # Output path MUST be last

        # Ensure output directory exists
        output_dir = os.path.dirname(output_path)
        os.makedirs(output_dir, exist_ok=True)

        logger.info(f"Executing FFmpeg: {' '.join(command)}")
        result = subprocess.run(command, capture_output=True, text=True, check=True)

        # Verify output file exists and has content
        if not os.path.exists(output_path) or os.path.getsize(output_path) == 0:
            raise RuntimeError("Output file was not created or is empty")

        logger.info(f"Conversion successful: {output_path} (size: {os.path.getsize(output_path)} bytes)")
        logger.debug(f"FFmpeg stdout: {result.stdout}")
        return 0

    except subprocess.CalledProcessError as e:
        logger.error(f"FFmpeg error (exit code {e.returncode}): {e.stderr}")
        print(f"Error during conversion (exit code {e.returncode}): {e.stderr}", file=sys.stderr)
        return 1
    except Exception as e:
        logger.error(f"Unexpected error: {str(e)}")
        print(f"Unexpected error: {str(e)}", file=sys.stderr)
        return 1

def validate_paths(input_path, output_path):
    """Validate input and output paths."""
    input_file = Path(input_path)
    if not input_file.exists():
        logger.error(f"Input file does not exist: {input_path}")
        return False

    output_dir = Path(output_path).parent
    try:
        output_dir.mkdir(parents=True, exist_ok=True)
        if not os.access(str(output_dir), os.W_OK):
            logger.error(f"Output directory not writable: {output_dir}")
            return False
    except Exception as e:
        logger.error(f"Failed to create output directory {output_dir}: {str(e)}")
        return False

    return True

if __name__ == "__main__":
    if len(sys.argv) != 4:
        print("Usage: python convert_media.py <input_path> <output_path> <target_format>",
              file=sys.stderr)
        sys.exit(1)

    input_path, output_path, target_format = sys.argv[1:4]

    # Validate paths
    if not validate_paths(input_path, output_path):
        sys.exit(1)

    # Perform conversion
    exit_code = convert_video(input_path, output_path, target_format)
    sys.exit(exit_code)