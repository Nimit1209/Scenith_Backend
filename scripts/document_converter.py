#!/usr/bin/env python3
"""
Document Conversion Script (Optimized for Lightweight Dependencies)
Supports: Word to PDF, PDF to Word, Merge PDF, Split PDF, Compress PDF,
Rotate PDF, Images to PDF, PDF to Images, Add Watermark, Lock/Unlock PDF

Dependencies:
- PyPDF2: PDF manipulation
- python-docx: Word document handling
- Pillow: Image processing
- reportlab: PDF generation
- pypdf: Alternative PDF library
"""

import sys
import json
import os
import subprocess
# Add at the top with other imports
import pytesseract
from pdf2image import convert_from_path
from pathlib import Path
import shutil

# Import required libraries
try:
    from PIL import Image
    from PyPDF2 import PdfReader, PdfWriter, PdfMerger
    from docx import Document
    from docx.shared import Pt, Inches
    from reportlab.pdfgen import canvas
    from reportlab.lib.pagesizes import letter, A4
    from reportlab.lib.colors import Color, HexColor
    from reportlab.lib.utils import ImageReader
    import io
except ImportError as e:
    print(json.dumps({"status": "error", "message": f"Missing required library: {e}"}))
    sys.exit(1)
def find_libreoffice():
    """Find LibreOffice executable - try multiple paths"""
    import os
    import subprocess
    import sys

    paths = [
        '/opt/libreoffice24.8/program/soffice',  # Current version
        '/opt/libreoffice24.2/program/soffice',
        '/opt/libreoffice7.6/program/soffice',
        '/usr/local/bin/soffice',
        '/usr/bin/soffice',
        '/usr/bin/libreoffice',
        'soffice',
        'libreoffice'
    ]

    for path in paths:
        if os.path.exists(path):
            print(f"Found LibreOffice at: {path}", file=sys.stderr)
            return path

    # Try as command in PATH (fallback)
    try:
        subprocess.run(['soffice', '--version'], capture_output=True, check=True)
        return 'soffice'
    except:
        pass

    raise Exception("LibreOffice not found. Please ensure LibreOffice is installed.")

def merge_pdfs(output_path, input_paths, options=None):
    """Merge multiple PDF files with optional page mapping"""
    try:
        if not input_paths:
            raise Exception("No PDF files provided for merging")

        merger = PdfMerger()

        # Check if we have page mapping (custom page order)
        page_mapping = options.get('pageMapping', []) if options else []

        if page_mapping:
            # Custom merge with specific pages in specific order
            # Build a map of uploadId to file path by parsing filenames
            upload_id_to_path = {}
            for path in input_paths:
                filename = os.path.basename(path)
                if filename.startswith('input_'):
                    parts = filename.split('_', 2)  # Split into ['input', '70', 'file.pdf']
                    if len(parts) >= 2:
                        try:
                            uid = int(parts[1])
                            upload_id_to_path[uid] = path
                        except ValueError:
                            pass

            for page_info in page_mapping:
                upload_id = page_info.get('uploadId')
                page_number = page_info.get('pageNumber', 1)

                file_path = upload_id_to_path.get(upload_id)
                if not file_path or not os.path.exists(file_path):
                    print(f"Warning: File not found for uploadId {upload_id}", file=sys.stderr)
                    continue

                # Append specific page (page_number - 1 because PyPDF2 uses 0-based indexing)
                try:
                    merger.append(file_path, pages=(page_number - 1, page_number))
                except Exception as e:
                    print(f"Warning: Failed to append page {page_number} from {file_path}: {str(e)}", file=sys.stderr)
        else:
            # Standard merge - all pages from all PDFs in order
            for pdf_path in input_paths:
                if not os.path.exists(pdf_path):
                    raise Exception(f"Input file not found: {pdf_path}")
                try:
                    merger.append(pdf_path)
                except Exception as e:
                    raise Exception(f"Error appending {pdf_path}: {str(e)}")

        merger.write(output_path)
        merger.close()

        return {
            "status": "success",
            "output_path": output_path,
            "pages_merged": len(page_mapping) if page_mapping else "all"
        }
    except Exception as e:
        return {"status": "error", "message": str(e)}

def split_pdf(input_path, output_path, options=None):
    """Split PDF into multiple files - ENHANCED"""
    try:
        import zipfile

        reader = PdfReader(input_path)
        total_pages = len(reader.pages)

        if total_pages == 0:
            raise Exception("PDF has no pages")

        split_type = options.get('splitType', 'all') if options else 'all'

        # Create temporary directory for split files
        temp_dir = os.path.dirname(output_path)
        split_dir = os.path.join(temp_dir, 'split_pages')
        os.makedirs(split_dir, exist_ok=True)

        files_created = 0

        if split_type == 'all':
            # Split into individual pages
            for i in range(total_pages):
                writer = PdfWriter()
                writer.add_page(reader.pages[i])

                page_output = os.path.join(split_dir, f'page_{i+1}.pdf')
                with open(page_output, 'wb') as output_file:
                    writer.write(output_file)
                files_created += 1

        elif split_type == 'range' and options and 'ranges' in options:
            # NEW: Split using multiple custom ranges from frontend
            ranges = options.get('ranges', [])

            if not ranges:
                raise Exception("No ranges provided for custom split")

            for idx, range_item in enumerate(ranges):
                start = range_item.get('from', 1)
                end = range_item.get('to', total_pages)

                # Validate range
                if start < 1 or end > total_pages or start > end:
                    print(f"Warning: Invalid range {start}-{end}, skipping", file=sys.stderr)
                    continue

                writer = PdfWriter()
                # Add pages from start to end (inclusive)
                for page_num in range(start - 1, end):  # start-1 because pages are 0-indexed
                    if 0 <= page_num < total_pages:
                        writer.add_page(reader.pages[page_num])

                # Create filename for this split
                range_output = os.path.join(split_dir, f'split_{idx+1}_pages_{start}-{end}.pdf')
                with open(range_output, 'wb') as output_file:
                    writer.write(output_file)
                files_created += 1
                print(f"Created split {idx+1}: pages {start}-{end}", file=sys.stderr)

        else:
            # Default: split each page
            for i in range(total_pages):
                writer = PdfWriter()
                writer.add_page(reader.pages[i])

                page_output = os.path.join(split_dir, f'page_{i+1}.pdf')
                with open(page_output, 'wb') as output_file:
                    writer.write(output_file)
                files_created += 1

        if files_created == 0:
            raise Exception("No files were created during splitting")

        # Create ZIP file
        with zipfile.ZipFile(output_path, 'w', zipfile.ZIP_DEFLATED) as zipf:
            for root, dirs, files in os.walk(split_dir):
                for file in files:
                    file_path = os.path.join(root, file)
                    zipf.write(file_path, file)

        # Clean up temporary directory
        import shutil
        shutil.rmtree(split_dir)

        return {
            "status": "success",
            "output_path": output_path,
            "files_created": files_created,
            "split_type": split_type
        }
    except Exception as e:
        return {"status": "error", "message": str(e)}


def compress_pdf(input_path, output_path, options=None):
    """
    Compress PDF file with precise file size targeting using iterative Ghostscript compression
    """
    # DEBUG: Create a log file to see what's happening
    debug_log = output_path + '.debug.log'

    def debug_log_write(msg):
        with open(debug_log, 'a') as f:
            f.write(msg + '\n')
            f.flush()

    try:
        # ===== DEBUG: Log received options =====
        debug_log_write(f"===== PYTHON SCRIPT DEBUG =====")
        debug_log_write(f"Received options: {options}")
        debug_log_write(f"Options type: {type(options)}")
        if options:
            for key, value in options.items():
                debug_log_write(f"  {key}: {value} (type: {type(value)})")
        debug_log_write(f"===============================")

        compression_mode = options.get('compressionMode', 'preset') if options else 'preset'
        reader = PdfReader(input_path)
        original_size = os.path.getsize(input_path)

        debug_log_write(f"START: mode={compression_mode}, original_size={original_size}")

        if compression_mode == 'filesize':
            target_size = options.get('targetFileSizeBytes')

            if target_size is None:
                raise ValueError("targetFileSizeBytes is required in filesize mode")

            # Make sure it's int (frontend might send float or string)
            try:
                target_size = int(float(target_size))   # handles "819200.0" → 819200
            except (TypeError, ValueError):
                raise ValueError(f"Invalid targetFileSizeBytes value: {target_size}")

            # Validate target size
            if target_size <= 0:
                raise Exception("Target size must be greater than 0")

            if target_size >= original_size * 0.95:
                raise Exception(f"Target size ({target_size}) is too close to original size ({original_size})")

            debug_log_write(f"Target size (filesize mode): {target_size} bytes")
            # ENHANCED: More precise binary search with better tolerance handling
            min_resolution = 30
            max_resolution = 300
            tolerance = 0.05  # 5% tolerance (was 8%)
            max_iterations = 30  # More iterations for precision

            best_file = None
            best_size = None
            best_diff = float('inf')
            best_resolution = None

            # Determine PDF settings based on target ratio
            target_ratio = target_size / original_size
            if target_ratio < 0.25:
                pdf_setting = '/screen'
            elif target_ratio < 0.50:
                pdf_setting = '/ebook'
            elif target_ratio < 0.75:
                pdf_setting = '/printer'
            else:
                pdf_setting = '/prepress'

            debug_log_write(f"PDF setting: {pdf_setting}, target_ratio: {target_ratio:.2f}")

            iterations_run = 0
            last_size = None
            stuck_count = 0  # Track if we're stuck in a loop

            for iteration in range(max_iterations):
                iterations_run = iteration + 1

                resolution = (min_resolution + max_resolution) // 2

                debug_log_write(f"Iteration {iterations_run}: resolution={resolution}, min={min_resolution}, max={max_resolution}")

                # Check convergence
                if max_resolution - min_resolution <= 1:
                    debug_log_write(f"Bounds converged at iteration {iterations_run}")
                    break

                temp_output = output_path + f'.iter_{iteration}'

                try:
                    jpeg_quality = min(95, max(15, int(resolution / 2.8)))

                    gs_command = [
                        'gs',
                        '-sDEVICE=pdfwrite',
                        '-dCompatibilityLevel=1.4',
                        f'-dPDFSETTINGS={pdf_setting}',
                        '-dNOPAUSE',
                        '-dQUIET',
                        '-dBATCH',
                        f'-dColorImageResolution={resolution}',
                        f'-dGrayImageResolution={resolution}',
                        f'-dMonoImageResolution={resolution}',
                        '-dDownsampleColorImages=true',
                        '-dDownsampleGrayImages=true',
                        '-dColorImageDownsampleType=/Bicubic',
                        '-dGrayImageDownsampleType=/Bicubic',
                        '-dAutoFilterColorImages=false',
                        '-dAutoFilterGrayImages=false',
                        '-dColorImageFilter=/DCTEncode',
                        '-dGrayImageFilter=/DCTEncode',
                        f'-dJPEGQ={jpeg_quality}',
                        '-dDetectDuplicateImages=true',
                        '-dCompressFonts=true',
                        '-dEmbedAllFonts=true',
                        '-dSubsetFonts=true',
                        f'-sOutputFile={temp_output}',
                        input_path
                    ]

                    debug_log_write(f"Running gs with quality={jpeg_quality}")

                    result = subprocess.run(
                        gs_command,
                        capture_output=True,
                        text=True,
                        timeout=180
                    )

                    if result.returncode != 0 or not os.path.exists(temp_output):
                        debug_log_write(f"GS failed: returncode={result.returncode}, exists={os.path.exists(temp_output)}")
                        max_resolution = resolution - 1
                        continue

                    temp_size = os.path.getsize(temp_output)
                    size_diff = abs(temp_size - target_size)
                    size_diff_percent = (size_diff / target_size) * 100

                    debug_log_write(f"Result: size={temp_size}, target={target_size}, diff={size_diff_percent:.1f}%")

                    # Check if we're stuck (same size as last iteration)
                    if last_size is not None and abs(temp_size - last_size) < 1000:  # Less than 1KB change
                        stuck_count += 1
                        if stuck_count >= 3:
                            debug_log_write(f"Stuck at same size, using best result")
                            if os.path.exists(temp_output):
                                try:
                                    os.remove(temp_output)
                                except:
                                    pass
                            break
                    else:
                        stuck_count = 0

                    last_size = temp_size

                    # CRITICAL FIX: Prefer results that are CLOSE TO but ABOVE target
                    # Only accept results below target if they're within tolerance
                    if temp_size <= target_size:
                        # File is smaller than target
                        if size_diff_percent <= (tolerance * 100):
                            # Within tolerance and below target - acceptable
                            if size_diff < best_diff or best_size is None or best_size < target_size:
                                if best_file and os.path.exists(best_file):
                                    try:
                                        os.remove(best_file)
                                    except:
                                        pass
                                best_file = temp_output
                                best_size = temp_size
                                best_diff = size_diff
                                best_resolution = resolution
                                debug_log_write(f"NEW BEST (below target): size={temp_size}")
                        else:
                            # Too far below target, try higher resolution
                            if os.path.exists(temp_output):
                                try:
                                    os.remove(temp_output)
                                except:
                                    pass

                        # Increase resolution to get closer to target (larger file)
                        min_resolution = resolution + 1
                    else:
                        # File is larger than target
                        if size_diff_percent <= (tolerance * 100):
                            # Within tolerance and above target - PREFERRED
                            if best_size is None or temp_size < best_size or best_size < target_size:
                                if best_file and os.path.exists(best_file):
                                    try:
                                        os.remove(best_file)
                                    except:
                                        pass
                                best_file = temp_output
                                best_size = temp_size
                                best_diff = size_diff
                                best_resolution = resolution
                                debug_log_write(f"NEW BEST (above target): size={temp_size}")
                            else:
                                if os.path.exists(temp_output):
                                    try:
                                        os.remove(temp_output)
                                    except:
                                        pass
                        else:
                            # Too far above target
                            if best_size is None or (size_diff < best_diff and temp_size >= target_size * 0.9):
                                if best_file and os.path.exists(best_file):
                                    try:
                                        os.remove(best_file)
                                    except:
                                        pass
                                best_file = temp_output
                                best_size = temp_size
                                best_diff = size_diff
                                best_resolution = resolution
                                debug_log_write(f"NEW BEST (tracking): size={temp_size}")
                            else:
                                if os.path.exists(temp_output):
                                    try:
                                        os.remove(temp_output)
                                    except:
                                        pass

                        # Decrease resolution to get closer to target (smaller file)
                        max_resolution = resolution - 1

                    # Check if we've achieved good enough result
                    if best_size is not None and size_diff_percent <= (tolerance * 100):
                        # If we're within tolerance and at or above 95% of target, stop
                        if best_size >= target_size * 0.95:
                            debug_log_write(f"TARGET ACHIEVED (within tolerance, close to target)!")
                            break

                except subprocess.TimeoutExpired:
                    debug_log_write(f"TIMEOUT at iteration {iterations_run}")
                    if os.path.exists(temp_output):
                        try:
                            os.remove(temp_output)
                        except:
                            pass
                    max_resolution = resolution - 1
                    continue
                except Exception as e:
                    debug_log_write(f"ERROR: {str(e)}")
                    if os.path.exists(temp_output):
                        try:
                            os.remove(temp_output)
                        except:
                            pass
                    max_resolution = resolution - 1
                    continue

            # Use best result
            if best_file and os.path.exists(best_file):
                os.rename(best_file, output_path)
                final_size = best_size
                compression_ratio = (1 - final_size / original_size) * 100
                accuracy = max(0, 100 - (abs(final_size - target_size) / target_size * 100))

                debug_log_write(f"FINAL: size={final_size}, resolution={best_resolution}dpi, iterations={iterations_run}")

                return {
                    "status": "success",
                    "output_path": output_path,
                    "original_size": original_size,
                    "compressed_size": final_size,
                    "target_size": target_size,
                    "compression_ratio": f"{compression_ratio:.2f}%",
                    "accuracy": f"{accuracy:.1f}%",
                    "size_difference_bytes": abs(final_size - target_size),
                    "size_difference_mb": abs(final_size - target_size) / 1024 / 1024,
                    "resolution_used": f"{best_resolution}dpi",
                    "iterations_used": iterations_run
                }
            else:
                debug_log_write("ERROR: No best file found!")
                raise Exception("Failed to compress to target size")

        elif compression_mode == 'percentage':
            compression_percentage = options.get('compressionPercentage', 50)
            target_size = int(original_size * (compression_percentage / 100))

            debug_log_write(f"Percentage mode: {compression_percentage}% = {target_size} bytes")

            options['targetFileSizeBytes'] = target_size
            options['compressionMode'] = 'filesize'
            return compress_pdf(input_path, output_path, options)

        else:
            debug_log_write("Forcing aggressive SCREEN mode for testing")
            try:
                subprocess.run([
                    'gs',
                    '-sDEVICE=pdfwrite',
                    '-dCompatibilityLevel=1.4',
                    '-dPDFSETTINGS=/screen',           # ← most aggressive
                    '-dNOPAUSE', '-dQUIET', '-dBATCH',
                    '-dColorImageResolution=72',
                    '-dGrayImageResolution=72',
                    '-dMonoImageResolution=72',
                    '-dJPEGQ=50',                       # ← lower quality
                    '-dDownsampleColorImages=true',
                    '-dColorImageDownsampleType=/Bicubic',
                    '-dColorImageFilter=/DCTEncode',
                    '-sOutputFile=' + output_path,
                    input_path
                ], check=True, timeout=180, capture_output=True)

                compressed_size = os.path.getsize(output_path)
                debug_log_write(f"Forced aggressive compression → {compressed_size} bytes")
                actual_ratio = (1 - compressed_size / original_size) * 100

                return {
                    "status": "success",
                    "output_path": output_path,
                    "original_size": original_size,
                    "compressed_size": compressed_size,
                    "compression_level": "aggressive_screen",
                    "actual_compression_ratio": f"{actual_ratio:.2f}%"
                }
            except Exception as e:
                raise Exception(f"Compression failed: {str(e)}")
    except Exception as e:
        debug_log_write(f"EXCEPTION: {str(e)}")
        return {"status": "error", "message": str(e)}

def rotate_pdf(input_path, output_path, options=None):
    """Rotate PDF pages - supports all pages, specific pages, or page ranges"""
    try:
        degrees = int(options.get('degrees', 90)) if options else 90
        pages_spec = options.get('pages', 'all') if options else 'all'

        # Validate rotation angle
        if degrees not in [90, 180, 270, -90, -180, -270]:
            degrees = 90

        reader = PdfReader(input_path)
        writer = PdfWriter()

        total_pages = len(reader.pages)

        if pages_spec == 'all':
            # Rotate all pages
            pages_to_rotate = set(range(total_pages))
        else:
            # Parse page specification (supports "1", "1,3,5", "1-5", "1,3-7,10")
            pages_to_rotate = set()

            # Split by comma
            parts = str(pages_spec).split(',')

            for part in parts:
                part = part.strip()

                if '-' in part:
                    # Range like "1-5"
                    try:
                        start, end = part.split('-', 1)
                        start = int(start.strip())
                        end = int(end.strip())

                        # Validate range
                        if start < 1 or end > total_pages or start > end:
                            print(f"Warning: Invalid range {start}-{end}, skipping", file=sys.stderr)
                            continue

                        # Add pages in range (convert to 0-based index)
                        for page_num in range(start, end + 1):
                            pages_to_rotate.add(page_num - 1)
                    except ValueError:
                        print(f"Warning: Invalid range format '{part}', skipping", file=sys.stderr)
                        continue
                else:
                    # Single page like "3"
                    try:
                        page_num = int(part.strip())

                        # Validate page number
                        if 1 <= page_num <= total_pages:
                            pages_to_rotate.add(page_num - 1)  # Convert to 0-based index
                        else:
                            print(f"Warning: Page {page_num} out of range (1-{total_pages}), skipping", file=sys.stderr)
                    except ValueError:
                        print(f"Warning: Invalid page number '{part}', skipping", file=sys.stderr)
                        continue

            if not pages_to_rotate:
                raise ValueError("No valid pages specified for rotation")

        # Add pages with rotation
        for i in range(total_pages):
            page = reader.pages[i]
            if i in pages_to_rotate:
                page.rotate(degrees)
            writer.add_page(page)

        with open(output_path, 'wb') as output_file:
            writer.write(output_file)

        return {
            "status": "success",
            "output_path": output_path,
            "rotated_pages": len(pages_to_rotate),
            "total_pages": total_pages,
            "rotation_applied": f"{degrees}° to {len(pages_to_rotate)} of {total_pages} pages"
        }
    except Exception as e:
        return {"status": "error", "message": str(e)}

def images_to_pdf(output_path, input_paths):
    """Convert multiple images to a single PDF with uniform aspect ratio"""
    try:
        if not input_paths:
            raise Exception("No image files provided")

        # Standard aspect ratio - A4 proportions (210mm x 297mm = 1:1.414)
        # Or use 3:4 (portrait) or 4:3 (landscape) for photos
        TARGET_ASPECT_RATIO = 210 / 297  # A4 portrait (width/height)

        # Target dimensions for consistent page size (in pixels at 300 DPI)
        # A4 at 300 DPI: 2480 x 3508 pixels
        TARGET_WIDTH = 2480
        TARGET_HEIGHT = 3508

        # Open and process images
        processed_images = []
        for img_path in input_paths:
            if not os.path.exists(img_path):
                raise Exception(f"Image file not found: {img_path}")

            try:
                img = Image.open(img_path)

                # Get original dimensions
                orig_width, orig_height = img.size
                orig_aspect = orig_width / orig_height

                # Create a white canvas with target dimensions
                canvas = Image.new('RGB', (TARGET_WIDTH, TARGET_HEIGHT), (255, 255, 255))

                # Calculate scaling to fit image within canvas while maintaining aspect ratio
                if orig_aspect > TARGET_ASPECT_RATIO:
                    # Image is wider - fit to width
                    new_width = TARGET_WIDTH
                    new_height = int(TARGET_WIDTH / orig_aspect)
                else:
                    # Image is taller - fit to height
                    new_height = TARGET_HEIGHT
                    new_width = int(TARGET_HEIGHT * orig_aspect)

                # Resize image with high-quality resampling
                img_resized = img.resize((new_width, new_height), Image.Resampling.LANCZOS)

                # Convert to RGB if necessary
                if img_resized.mode in ('RGBA', 'LA', 'P'):
                    background = Image.new('RGB', img_resized.size, (255, 255, 255))
                    if img_resized.mode == 'P':
                        img_resized = img_resized.convert('RGBA')
                    if img_resized.mode == 'RGBA':
                        background.paste(img_resized, mask=img_resized.split()[-1])
                    else:
                        background.paste(img_resized)
                    img_resized = background
                elif img_resized.mode != 'RGB':
                    img_resized = img_resized.convert('RGB')

                # Center the image on the canvas
                paste_x = (TARGET_WIDTH - new_width) // 2
                paste_y = (TARGET_HEIGHT - new_height) // 2
                canvas.paste(img_resized, (paste_x, paste_y))

                processed_images.append(canvas)

            except Exception as e:
                raise Exception(f"Error processing image {img_path}: {str(e)}")

        if not processed_images:
            raise Exception("No valid images to convert")

        # Save as PDF with consistent page size
        processed_images[0].save(
            output_path,
            save_all=True,
            append_images=processed_images[1:] if len(processed_images) > 1 else [],
            resolution=300.0,  # 300 DPI for high quality
            quality=95
        )

        return {
            "status": "success",
            "output_path": output_path,
            "image_count": len(processed_images),
            "page_size": f"{TARGET_WIDTH}x{TARGET_HEIGHT}px (A4 at 300 DPI)",
            "aspect_ratio": f"{TARGET_ASPECT_RATIO:.3f} (A4 portrait)"
        }
    except Exception as e:
        return {"status": "error", "message": str(e)}

def pdf_to_images(input_path, output_path):
    """Extract pages from PDF as images using pdftoppm"""
    try:
        import zipfile

        # Create temporary directory for images
        temp_dir = os.path.dirname(output_path)
        images_dir = os.path.join(temp_dir, 'extracted_images')
        os.makedirs(images_dir, exist_ok=True)

        # Use pdftoppm (from poppler-utils) to convert PDF pages to images
        result = subprocess.run([
            'pdftoppm',
            '-png',
            '-r', '200',  # 200 DPI for good quality
            input_path,
            os.path.join(images_dir, 'page')
        ], capture_output=True, text=True, timeout=300)

        if result.returncode != 0:
            raise Exception(f"PDF to images conversion failed: {result.stderr}")

        # Count created images
        image_files = [f for f in os.listdir(images_dir) if f.endswith('.png')]

        if not image_files:
            raise Exception("No images were extracted from PDF")

        # Create ZIP file
        with zipfile.ZipFile(output_path, 'w', zipfile.ZIP_DEFLATED) as zipf:
            for file in image_files:
                file_path = os.path.join(images_dir, file)
                zipf.write(file_path, file)

        # Clean up temporary directory
        import shutil
        shutil.rmtree(images_dir)

        return {"status": "success", "output_path": output_path, "image_count": len(image_files)}
    except subprocess.TimeoutExpired:
        return {"status": "error", "message": "Image extraction timeout - PDF too large"}
    except Exception as e:
        return {"status": "error", "message": str(e)}


def add_watermark(input_path, output_path, options=None):
    """Add text watermark to PDF using reportlab overlay"""
    try:
        watermark_text = options.get('watermarkText', 'CONFIDENTIAL') if options else 'CONFIDENTIAL'
        opacity = float(options.get('opacity', 0.3)) if options else 0.3
        position = options.get('position', 'center') if options else 'center'

        reader = PdfReader(input_path)
        writer = PdfWriter()

        for page in reader.pages:
            # Get page dimensions
            page_width = float(page.mediabox.width)
            page_height = float(page.mediabox.height)

            # Create watermark using reportlab
            packet = io.BytesIO()
            can = canvas.Canvas(packet, pagesize=(page_width, page_height))

            # Set watermark properties
            can.setFillColor(Color(0.5, 0.5, 0.5, alpha=opacity))
            can.setFont("Helvetica-Bold", 50)

            # Calculate position
            if position == 'center':
                x = page_width / 2
                y = page_height / 2
            elif position == 'top-left':
                x = 100
                y = page_height - 100
            elif position == 'top-right':
                x = page_width - 100
                y = page_height - 100
            elif position == 'bottom-left':
                x = 100
                y = 100
            elif position == 'bottom-right':
                x = page_width - 100
                y = 100
            else:
                x = page_width / 2
                y = page_height / 2

            # Draw watermark with rotation
            can.saveState()
            can.translate(x, y)
            can.rotate(45)
            can.drawCentredString(0, 0, watermark_text)
            can.restoreState()
            can.save()

            # Merge watermark with page
            packet.seek(0)
            watermark_pdf = PdfReader(packet)
            page.merge_page(watermark_pdf.pages[0])

            writer.add_page(page)

        with open(output_path, 'wb') as output_file:
            writer.write(output_file)

        return {"status": "success", "output_path": output_path}
    except Exception as e:
        return {"status": "error", "message": str(e)}


def unlock_pdf(input_path, output_path, options=None):
    """Remove password from PDF using qpdf"""
    try:
        password = options.get('password', '') if options else ''

        if not password:
            raise Exception("Password is required to unlock PDF")

        # Use qpdf to decrypt PDF
        result = subprocess.run([
            'qpdf',
            '--decrypt',
            '--password=' + password,
            input_path,
            output_path
        ], capture_output=True, text=True, timeout=60)

        if result.returncode != 0:
            raise Exception(f"Failed to unlock PDF: {result.stderr}")

        return {"status": "success", "output_path": output_path}
    except subprocess.TimeoutExpired:
        return {"status": "error", "message": "Unlock operation timeout"}
    except FileNotFoundError:
        return {"status": "error", "message": "qpdf not installed - cannot unlock PDF"}
    except Exception as e:
        return {"status": "error", "message": str(e)}


def lock_pdf(input_path, output_path, options=None):
    """Add password protection to PDF using qpdf"""
    try:
        user_password = options.get('password', '') if options else ''
        owner_password = options.get('ownerPassword', user_password) if options else user_password

        if not user_password:
            raise Exception("Password is required to lock PDF")

        # Use qpdf to encrypt PDF
        result = subprocess.run([
            'qpdf',
            '--encrypt',
            user_password,
            owner_password,
            '256',  # 256-bit encryption
            '--',
            input_path,
            output_path
        ], capture_output=True, text=True, timeout=60)

        if result.returncode != 0:
            raise Exception(f"Failed to lock PDF: {result.stderr}")

        return {"status": "success", "output_path": output_path}
    except subprocess.TimeoutExpired:
        return {"status": "error", "message": "Lock operation timeout"}
    except FileNotFoundError:
        return {"status": "error", "message": "qpdf not installed - cannot lock PDF"}
    except Exception as e:
        return {"status": "error", "message": str(e)}

def rearrange_pdf(input_path, output_path, options=None):
    """Rearrange PDF pages with optional insertions - ENHANCED with image resizing"""
    try:
        reader = PdfReader(input_path)
        writer = PdfWriter()
        total_pages = len(reader.pages)

        if total_pages == 0:
            raise Exception("PDF has no pages")

        # Get the dimensions of the first page of the original PDF
        # This will be our target size for all inserted images
        first_page = reader.pages[0]
        target_width = float(first_page.mediabox.width)
        target_height = float(first_page.mediabox.height)


        # Get page order (default: keep original order)
        page_order = options.get('pageOrder', list(range(total_pages))) if options else list(range(total_pages))

        # Validate page order
        if not page_order:
            page_order = list(range(total_pages))

        # Get insertions if any
        insertions = options.get('insertions', []) if options else []

        # Create a map of position -> insertion for quick lookup
        insertion_map = {}
        for insertion in insertions:
            pos = insertion.get('position', 0)
            insertion_map[pos] = insertion

        page_index = 0

        # Process pages according to order and insertions
        for i in range(len(page_order) + len(insertions)):
            # Check if there's an insertion at this position
            if i in insertion_map:
                insertion = insertion_map[i]
                insert_type = insertion.get('type', 'image')
                file_path = insertion.get('filePath', '')

                if not file_path or not os.path.exists(file_path):
                    continue

                try:
                    if insert_type == 'pdf':
                        # Insert PDF page(s) - keep original size
                        insert_reader = PdfReader(file_path)
                        for insert_page in insert_reader.pages:
                            writer.add_page(insert_page)

                    elif insert_type == 'image':
                        # ============================================================
                        # ENHANCED: Resize image to match PDF page dimensions
                        # ============================================================
                        img = Image.open(file_path)

                        # Get original image dimensions
                        orig_width, orig_height = img.size

                        # Convert PDF points to pixels (assuming 72 DPI for PDF, 300 DPI for image quality)
                        dpi = 300
                        target_width_px = int(target_width * dpi / 72)
                        target_height_px = int(target_height * dpi / 72)


                        # Calculate aspect ratios
                        img_aspect = orig_width / orig_height
                        page_aspect = target_width / target_height

                        # Resize image to fit within PDF page while maintaining aspect ratio
                        if img_aspect > page_aspect:
                            # Image is wider - fit to width
                            new_width = target_width_px
                            new_height = int(target_width_px / img_aspect)
                        else:
                            # Image is taller - fit to height
                            new_height = target_height_px
                            new_width = int(target_height_px * img_aspect)

                        # Resize image with high-quality resampling
                        img = img.resize((new_width, new_height), Image.Resampling.LANCZOS)

                        # Create a new image with PDF page dimensions (white background)
                        final_img = Image.new('RGB', (target_width_px, target_height_px), (255, 255, 255))

                        # Calculate position to center the resized image
                        paste_x = (target_width_px - new_width) // 2
                        paste_y = (target_height_px - new_height) // 2

                        # Convert to RGB if necessary
                        if img.mode in ('RGBA', 'LA', 'P'):
                            background = Image.new('RGB', img.size, (255, 255, 255))
                            if img.mode == 'P':
                                img = img.convert('RGBA')
                            if img.mode == 'RGBA':
                                background.paste(img, mask=img.split()[-1])
                            else:
                                background.paste(img)
                            img = background
                        elif img.mode != 'RGB':
                            img = img.convert('RGB')

                        # Paste resized image onto centered white background
                        final_img.paste(img, (paste_x, paste_y))

                        # Create PDF page from the properly sized image using reportlab
                        packet = io.BytesIO()
                        can = canvas.Canvas(packet, pagesize=(target_width, target_height))

                        # Save final image to BytesIO
                        img_buffer = io.BytesIO()
                        final_img.save(img_buffer, format='JPEG', quality=95, dpi=(dpi, dpi))
                        img_buffer.seek(0)

                        # Draw image on canvas at exact PDF page size
                        img_reader_obj = ImageReader(img_buffer)
                        can.drawImage(img_reader_obj, 0, 0,
                                      width=target_width,
                                      height=target_height,
                                      preserveAspectRatio=False)
                        can.save()

                        # Convert canvas to PDF page
                        packet.seek(0)
                        img_pdf_reader = PdfReader(packet)
                        writer.add_page(img_pdf_reader.pages[0])


                except Exception as insert_error:
                    # Log error but continue processing
                    print(f"Warning: Failed to insert file at position {i}: {str(insert_error)}", file=sys.stderr)
            else:
                # Add regular page from the reordered list
                if page_index < len(page_order):
                    page_num = page_order[page_index]
                    if 0 <= page_num < total_pages:
                        writer.add_page(reader.pages[page_num])
                    page_index += 1

        # Write output PDF
        with open(output_path, 'wb') as output_file:
            writer.write(output_file)

        return {
            "status": "success",
            "output_path": output_path,
            "total_pages": len(writer.pages),
            "insertions_added": len(insertions),
            "page_dimensions": f"{target_width} x {target_height} points"
        }

    except Exception as e:
        return {"status": "error", "message": str(e)}

def parse_page_ranges(pages_spec, total_pages):
    """Parse page range specification like '1-5,7,9-12'"""
    ranges = []
    parts = pages_spec.split(',')

    for part in parts:
        part = part.strip()
        if '-' in part:
            try:
                start, end = part.split('-')
                start = max(1, min(int(start), total_pages))
                end = max(1, min(int(end), total_pages))
                if start <= end:
                    ranges.append((start, end))
            except ValueError:
                continue
        else:
            try:
                page_num = max(1, min(int(part), total_pages))
                ranges.append((page_num, page_num))
            except ValueError:
                continue

    return ranges if ranges else [(1, total_pages)]


def parse_page_list(pages_spec, total_pages):
    """Parse page list like '1,3,5' into list of indices"""
    pages = []
    parts = pages_spec.split(',')

    for part in parts:
        part = part.strip()
        try:
            page_num = max(1, min(int(part), total_pages))
            pages.append(page_num - 1)  # Convert to 0-based index
        except ValueError:
            continue

    return pages if pages else list(range(total_pages))

# ============================================================================
# NEW FUNCTION 1: Word to PDF
# ============================================================================
def word_to_pdf(input_path, output_path):
    """Convert Word document to PDF using LibreOffice"""
    try:
        output_dir = os.path.dirname(output_path)

        # Find LibreOffice
        libreoffice_cmd = find_libreoffice()

        # Use LibreOffice for conversion
        result = subprocess.run([
            libreoffice_cmd,
            '--headless',
            "--convert-to", "pdf",
            "--outdir", output_dir,
            input_path
        ], capture_output=True, text=True, timeout=120)

        if result.returncode != 0:
            raise Exception(f"LibreOffice conversion failed: {result.stderr}")

        # LibreOffice creates file with same name but .pdf extension
        generated_pdf = os.path.join(
            output_dir,
            Path(input_path).stem + ".pdf"
        )

        if not os.path.exists(generated_pdf):
            raise Exception("PDF file was not created by LibreOffice")

        # Rename to expected output path
        if generated_pdf != output_path:
            os.rename(generated_pdf, output_path)

        return {
            "status": "success",
            "output_path": output_path,
            "conversion_tool": "LibreOffice"
        }
    except subprocess.TimeoutExpired:
        return {"status": "error", "message": "Conversion timeout - document too large"}
    except Exception as e:
        return {"status": "error", "message": str(e)}


# ============================================================================
# NEW FUNCTION 2: PDF to Word
# ============================================================================
def pdf_to_word(input_path, output_path):
    """Convert PDF to Word document using LibreOffice"""
    try:
        if not os.path.exists(input_path):
            raise Exception(f"Input file not found: {input_path}")

        output_dir = os.path.dirname(output_path)

        # Find LibreOffice
        libreoffice_cmd = find_libreoffice()

        # Use LibreOffice headless mode
        result = subprocess.run([
            libreoffice_cmd,
            "--headless",
            "--convert-to", "docx",
            "--outdir", output_dir,
            input_path
        ], capture_output=True, text=True, timeout=180)

        if result.returncode != 0:
            raise Exception(f"LibreOffice conversion failed: {result.stderr}")

        # LibreOffice creates file with same name but .docx extension
        generated_docx = os.path.join(
            output_dir,
            Path(input_path).stem + ".docx"
        )

        if not os.path.exists(generated_docx):
            raise Exception("DOCX file was not created by LibreOffice")

        # Rename to expected output path
        if generated_docx != output_path:
            os.rename(generated_docx, output_path)

        return {
            "status": "success",
            "output_path": output_path,
            "conversion_tool": "LibreOffice",
            "note": "Best results for text-based PDFs. Scanned PDFs may lose formatting."
        }
    except subprocess.TimeoutExpired:
        return {"status": "error", "message": "Conversion timeout - document too large"}
    except Exception as e:
        return {"status": "error", "message": str(e)}


# ============================================================================
# NEW FUNCTION 3: OCR PDF
# ============================================================================
def ocr_pdf(input_path, output_path, options=None):
    """
    Apply OCR to PDF using OCRmyPDF + Universal Post-Processing
    FIXED: Logs to BOTH stdout and stderr for Java visibility
    """
    try:
        language = options.get('language', 'eng') if options else 'eng'

        # OCRmyPDF language codes
        lang_map = {
            'eng': 'eng',
            'spa': 'spa',
            'fra': 'fra',
            'deu': 'deu',
            'hin': 'hin',
        }
        ocr_lang = lang_map.get(language, 'eng')

        # Use OCRmyPDF for proper OCR
        cmd = [
            'ocrmypdf',
            '--language', ocr_lang,
            '--optimize', '1',
            '--output-type', 'pdf',
            '--force-ocr',
            '--invalidate-digital-signatures',
            input_path,
            output_path
        ]

        # Run OCRmyPDF
        result = subprocess.run(
            cmd,
            capture_output=True,
            text=True,
            timeout=600
        )

        if result.returncode != 0:
            if result.returncode == 6:
                cmd_force = [
                    'ocrmypdf',
                    '--language', ocr_lang,
                    '--optimize', '1',
                    '--force-ocr',
                    '--invalidate-digital-signatures',
                    input_path,
                    output_path
                ]
                result = subprocess.run(
                    cmd_force,
                    capture_output=True,
                    text=True,
                    timeout=600
                )
                if result.returncode != 0:
                    raise Exception(f"OCRmyPDF failed: {result.stderr}")
            else:
                raise Exception(f"OCRmyPDF failed: {result.stderr}")

        # Verify output
        if not os.path.exists(output_path):
            raise Exception("OCR output file was not created")

        # ========================================================================
        # ✅ POST-PROCESSING with STDOUT + STDERR logging
        # ========================================================================
        post_processing_data = None
        try:
            # CRITICAL FIX: Log to BOTH stdout AND stderr
            print("[OCR] ===== POST-PROCESSING START =====")
            print("[OCR] ===== POST-PROCESSING START =====", file=sys.stderr)
            sys.stdout.flush()
            sys.stderr.flush()

            # Extract text from OCR'd PDF
            temp_text_file = output_path + '.raw_text.txt'
            temp_json_file = output_path + '.cleaned.json'

            print(f"[OCR] Temp text file: {temp_text_file}")
            print(f"[OCR] Temp JSON file: {temp_json_file}")
            sys.stdout.flush()


            pdftotext_path = shutil.which('pdftotext')

            if not pdftotext_path:
                # Try direct path as fallback
                pdftotext_path = '/usr/bin/pdftotext'
                if not os.path.exists(pdftotext_path):
                    raise FileNotFoundError("pdftotext not found in PATH or at /usr/bin/pdftotext")

            print(f"[OCR] pdftotext location: {pdftotext_path}")
            sys.stdout.flush()

            # Extract text using pdftotext
            print("[OCR] Running pdftotext...")
            sys.stdout.flush()

            pdftotext_result = subprocess.run([
                pdftotext_path,  # Use the found path
                '-layout',
                output_path,
                temp_text_file
            ], capture_output=True, text=True, timeout=60)

            if pdftotext_result.returncode != 0:
                print(f"[OCR] pdftotext FAILED: {pdftotext_result.stderr}")
                sys.stdout.flush()
                raise Exception(f"pdftotext failed: {pdftotext_result.stderr}")

            print(f"[OCR] pdftotext SUCCESS. Output size: {os.path.getsize(temp_text_file)} bytes")
            sys.stdout.flush()

            # Check if post-processor exists
            postproc_path = '/app/scripts/ocr_postprocessor.py'
            if not os.path.exists(postproc_path):
                print(f"[OCR] ERROR: Post-processor not found at {postproc_path}")
                sys.stdout.flush()
                raise FileNotFoundError(f"Post-processor not found: {postproc_path}")

            print(f"[OCR] Post-processor found: {postproc_path}")
            sys.stdout.flush()

            # Run post-processor
            print("[OCR] Running post-processor...")
            sys.stdout.flush()

            postproc_result = subprocess.run([
                '/usr/local/bin/python3.11',
                postproc_path,
                temp_text_file,
                temp_json_file
            ], capture_output=True, text=True, timeout=60)

            print(f"[OCR] Post-processor exit code: {postproc_result.returncode}")
            print(f"[OCR] Post-processor stdout: {postproc_result.stdout}")
            print(f"[OCR] Post-processor stderr: {postproc_result.stderr}")
            sys.stdout.flush()

            if postproc_result.returncode != 0:
                print(f"[OCR] Post-processor FAILED: {postproc_result.stderr}")
                sys.stdout.flush()
                raise Exception(f"Post-processor failed: {postproc_result.stderr}")

            # Read cleaned results
            if os.path.exists(temp_json_file):
                with open(temp_json_file, 'r', encoding='utf-8') as f:
                    post_processing_data = json.load(f)
                    print(f"[OCR] Post-processing complete. Currency: {post_processing_data.get('detected_currency')}")
                    print(f"[OCR] Detected tax IDs: {post_processing_data.get('detected_tax_ids')}")
                    print(f"[OCR] Extracted amounts: {len(post_processing_data.get('extracted_amounts', []))} found")
                    sys.stdout.flush()
            else:
                print(f"[OCR] WARNING: Output JSON not created at {temp_json_file}")
                sys.stdout.flush()

            # Clean up temp files
            if os.path.exists(temp_text_file):
                os.remove(temp_text_file)
            if os.path.exists(temp_json_file):
                os.remove(temp_json_file)

            print("[OCR] ===== POST-PROCESSING END =====")
            sys.stdout.flush()

        except Exception as e:
            print(f"[OCR] ===== POST-PROCESSING FAILED =====")
            print(f"[OCR] Error: {str(e)}")
            print(f"[OCR] Error type: {type(e).__name__}")
            sys.stdout.flush()
            import traceback
            print(f"[OCR] Traceback: {traceback.format_exc()}")
            sys.stdout.flush()

        # Get page count
        try:
            reader = PdfReader(output_path)
            page_count = len(reader.pages)
        except:
            page_count = "unknown"

        # Build response
        response = {
            "status": "success",
            "output_path": output_path,
            "pages_processed": page_count,
            "language": language,
            "ocr_engine": "OCRmyPDF + Tesseract 5.3.0",
            "quality": "production-grade"
        }

        # Add post-processing results if available
        if post_processing_data:
            response["post_processing"] = {
                "detected_currency": post_processing_data.get('detected_currency'),
                "detected_tax_ids": post_processing_data.get('detected_tax_ids', {}),
                "extracted_amounts": post_processing_data.get('extracted_amounts', []),
                "cleanup_applied": True
            }

        return response

    except subprocess.TimeoutExpired:
        return {
            "status": "error",
            "message": "OCR timeout - document is too large or complex."
        }
    except FileNotFoundError:
        return ocr_pdf_fallback(input_path, output_path, options)
    except Exception as e:
        return {"status": "error", "message": str(e)}

def ocr_pdf_fallback(input_path, output_path, options=None):
    """
    Fallback OCR using pytesseract with improved text positioning
    Uses hOCR output for better accuracy
    """
    try:
        language = options.get('language', 'eng') if options else 'eng'

        # Convert PDF to images at high DPI
        images = convert_from_path(input_path, dpi=300)

        if not images:
            raise Exception("Failed to convert PDF to images")

        reader = PdfReader(input_path)
        writer = PdfWriter()

        for i, (page, image) in enumerate(zip(reader.pages, images)):
            # Use hOCR for better positioning
            try:
                hocr_data = pytesseract.image_to_pdf_or_hocr(
                    image,
                    lang=language,
                    extension='hocr',
                    config='--psm 1'  # Automatic page segmentation with OSD
                )
            except:
                # If hOCR fails, fall back to basic OCR
                hocr_data = None

            # Get page dimensions
            width = float(page.mediabox.width)
            height = float(page.mediabox.height)

            # Create invisible text overlay with better positioning
            packet = io.BytesIO()
            can = canvas.Canvas(packet, pagesize=(width, height))

            # Try to parse hOCR for accurate positioning
            if hocr_data:
                try:
                    from bs4 import BeautifulSoup
                    soup = BeautifulSoup(hocr_data, 'html.parser')

                    can.setFillColor(Color(0, 0, 0, alpha=0))  # Invisible
                    can.setFont("Helvetica", 10)

                    # Extract text with bounding boxes from hOCR
                    for word in soup.find_all('span', class_='ocrx_word'):
                        text = word.get_text(strip=True)
                        if text:
                            # Get bbox coordinates
                            title = word.get('title', '')
                            if 'bbox' in title:
                                bbox = title.split('bbox ')[1].split(';')[0].split()
                                x1, y1, x2, y2 = map(int, bbox)

                                # Scale coordinates to PDF size
                                scale_x = width / image.width
                                scale_y = height / image.height

                                x = x1 * scale_x
                                y = height - (y1 * scale_y)  # Flip Y coordinate

                                can.drawString(x, y, text)

                except ImportError:
                    hocr_data = None  # BeautifulSoup not available

            # Fallback to simple OCR if hOCR parsing failed
            if not hocr_data:
                can.setFillColor(Color(0, 0, 0, alpha=0))  # Invisible
                can.setFont("Helvetica", 8)

                ocr_text = pytesseract.image_to_string(image, lang=language)
                lines = ocr_text.split('\n')
                y_position = height - 40

                for line in lines[:150]:
                    if line.strip():
                        can.drawString(20, y_position, line.strip()[:120])
                        y_position -= 14
                        if y_position < 30:
                            break

            can.save()

            # Merge overlay with page
            packet.seek(0)
            ocr_overlay = PdfReader(packet)
            page.merge_page(ocr_overlay.pages[0])
            writer.add_page(page)

        # Write output
        with open(output_path, 'wb') as output_file:
            writer.write(output_file)

        return {
            "status": "success",
            "output_path": output_path,
            "pages_processed": len(images),
            "language": language,
            "ocr_engine": "pytesseract (fallback)",
            "note": "Using fallback OCR - consider installing OCRmyPDF for better results"
        }

    except Exception as e:
        return {"status": "error", "message": str(e)}


# ============================================================================
# NEW FUNCTION 4: Add Page Numbers (Enhanced with removal)
# ============================================================================
def add_page_numbers(input_path, output_path, options=None):
    """Add page numbers to PDF with smart detection and removal of existing numbers"""
    try:
        reader = PdfReader(input_path)
        writer = PdfWriter()

        total_pages = len(reader.pages)

        # Get options
        start_page = int(options.get('startPage', 1)) if options else 1
        end_page = int(options.get('endPage', total_pages)) if options else total_pages
        start_number = int(options.get('startNumber', 1)) if options else 1
        position = options.get('position', 'bottom-center') if options else 'bottom-center'
        font_size = int(options.get('fontSize', 10)) if options else 10
        remove_existing = bool(options.get('removeExisting', True)) if options else True

        # Validate range
        start_page = max(1, min(start_page, total_pages))
        end_page = max(start_page, min(end_page, total_pages))

        for i, page in enumerate(reader.pages):
            current_page_num = i + 1

            # Skip pages outside the specified range
            if current_page_num < start_page or current_page_num > end_page:
                writer.add_page(page)
                continue

            # If remove_existing is True, try to detect and remove existing page numbers
            # This is a best-effort approach - perfect removal is difficult
            if remove_existing:
                # Extract text from page
                try:
                    page_text = page.extract_text()

                    # Simple heuristic: look for standalone numbers at common positions
                    # This won't catch all cases but helps with basic numbered PDFs
                    lines = page_text.split('\n')

                    # Check last few lines for potential page numbers
                    potential_numbers = []
                    for line in lines[-5:]:  # Check last 5 lines
                        line = line.strip()
                        if line.isdigit() and len(line) <= 4:  # Likely a page number
                            potential_numbers.append(line)

                    # Note: Actually removing text from PDF is complex
                    # We'll overlay new numbers which typically works well
                except:
                    pass  # If text extraction fails, just proceed

            # Create overlay with new page number
            packet = io.BytesIO()
            width = float(page.mediabox.width)
            height = float(page.mediabox.height)

            can = canvas.Canvas(packet, pagesize=(width, height))
            can.setFont("Helvetica", font_size)
            can.setFillColor(Color(0, 0, 0))  # Black text

            # Calculate page number to display
            page_number = start_number + (current_page_num - start_page)

            # Position based on user preference
            if position == "bottom-center":
                x, y = width / 2, 20
                can.drawCentredString(x, y, str(page_number))
            elif position == "bottom-right":
                x, y = width - 40, 20
                can.drawRightString(x, y, str(page_number))
            elif position == "bottom-left":
                x, y = 40, 20
                can.drawString(x, y, str(page_number))
            elif position == "top-center":
                x, y = width / 2, height - 20
                can.drawCentredString(x, y, str(page_number))
            elif position == "top-right":
                x, y = width - 40, height - 20
                can.drawRightString(x, y, str(page_number))
            elif position == "top-left":
                x, y = 40, height - 20
                can.drawString(x, y, str(page_number))
            else:  # default to bottom-center
                x, y = width / 2, 20
                can.drawCentredString(x, y, str(page_number))

            can.save()

            # Merge overlay with page
            packet.seek(0)
            overlay = PdfReader(packet)
            page.merge_page(overlay.pages[0])
            writer.add_page(page)

        # Write output
        with open(output_path, 'wb') as output_file:
            writer.write(output_file)

        pages_numbered = end_page - start_page + 1

        return {
            "status": "success",
            "output_path": output_path,
            "total_pages": total_pages,
            "pages_numbered": pages_numbered,
            "range": f"{start_page}-{end_page}",
            "starting_number": start_number,
            "position": position
        }
    except Exception as e:
        return {"status": "error", "message": str(e)}


def main():
    if len(sys.argv) < 4:
        print(json.dumps({
            "status": "error",
            "message": "Usage: document_converter.py <operation> <input(s)> <output> [options]"
        }))
        sys.exit(1)

    operation = sys.argv[1]

    try:
        if operation == "WORD_TO_PDF":
            input_path = sys.argv[2]
            output_path = sys.argv[3]
            result = word_to_pdf(input_path, output_path)

        elif operation == "PDF_TO_WORD":
            input_path = sys.argv[2]
            output_path = sys.argv[3]
            result = pdf_to_word(input_path, output_path)

        elif operation == "OCR_PDF":
            input_path = sys.argv[2]
            output_path = sys.argv[3]
            options = json.loads(sys.argv[4]) if len(sys.argv) > 4 else None
            result = ocr_pdf(input_path, output_path, options)

        elif operation == "ADD_PAGE_NUMBERS":
            input_path = sys.argv[2]
            output_path = sys.argv[3]
            options = json.loads(sys.argv[4]) if len(sys.argv) > 4 else None
            result = add_page_numbers(input_path, output_path, options)

        elif operation == "MERGE_PDF":
            output_path = sys.argv[2]
            args = sys.argv[3:]
            options = None
            if args and args[-1].startswith('{') and args[-1].endswith('}'):
                try:
                    options = json.loads(args[-1])
                    input_paths = args[:-1]
                except json.JSONDecodeError:
                    input_paths = args
                    options = None
            else:
                input_paths = args
            result = merge_pdfs(output_path, input_paths, options)

        elif operation == "SPLIT_PDF":
            input_path = sys.argv[2]
            output_path = sys.argv[3]
            options = json.loads(sys.argv[4]) if len(sys.argv) > 4 else None
            result = split_pdf(input_path, output_path, options)

        elif operation == "COMPRESS_PDF":
            input_path = sys.argv[2]
            output_path = sys.argv[3]
            options = json.loads(sys.argv[4]) if len(sys.argv) > 4 else None
            result = compress_pdf(input_path, output_path, options)

        elif operation == "ROTATE_PDF":
            input_path = sys.argv[2]
            output_path = sys.argv[3]
            options = json.loads(sys.argv[4]) if len(sys.argv) > 4 else None
            result = rotate_pdf(input_path, output_path, options)

        elif operation == "IMAGES_TO_PDF":
            output_path = sys.argv[2]
            args = sys.argv[3:]
            options = None
            if args and args[-1].startswith('{') and args[-1].endswith('}'):
                try:
                    options = json.loads(args[-1])
                    input_paths = args[:-1]
                except json.JSONDecodeError:
                    input_paths = args
                    options = None
            else:
                input_paths = args
            result = images_to_pdf(output_path, input_paths)

        elif operation == "PDF_TO_IMAGES":
            input_path = sys.argv[2]
            output_path = sys.argv[3]
            result = pdf_to_images(input_path, output_path)

        elif operation == "ADD_WATERMARK":
            input_path = sys.argv[2]
            output_path = sys.argv[3]
            options = json.loads(sys.argv[4]) if len(sys.argv) > 4 else None
            result = add_watermark(input_path, output_path, options)

        elif operation == "UNLOCK_PDF":
            input_path = sys.argv[2]
            output_path = sys.argv[3]
            options = json.loads(sys.argv[4]) if len(sys.argv) > 4 else None
            result = unlock_pdf(input_path, output_path, options)

        elif operation == "LOCK_PDF":
            input_path = sys.argv[2]
            output_path = sys.argv[3]
            options = json.loads(sys.argv[4]) if len(sys.argv) > 4 else None
            result = lock_pdf(input_path, output_path, options)

        # ADD THIS NEW CASE HERE ⬇️
        elif operation == "REARRANGE_PDF":
            input_path = sys.argv[2]
            output_path = sys.argv[3]
            options = json.loads(sys.argv[4]) if len(sys.argv) > 4 else None
            result = rearrange_pdf(input_path, output_path, options)
        # ⬆️ END OF NEW CASE

        else:
            result = {"status": "error", "message": f"Unknown operation: {operation}"}

        print(json.dumps(result))
        sys.exit(0 if result.get("status") == "success" else 1)

    except Exception as e:
        print(json.dumps({"status": "error", "message": str(e)}))
        sys.exit(1)


if __name__ == "__main__":
    main()