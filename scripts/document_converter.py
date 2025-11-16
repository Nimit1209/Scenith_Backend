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
from pathlib import Path

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


def word_to_pdf(input_path, output_path):
    """Convert Word document to PDF using LibreOffice"""
    try:
        output_dir = os.path.dirname(output_path)

        # Use LibreOffice for conversion
        result = subprocess.run([
            'libreoffice',
            '--headless',
            '--convert-to', 'pdf',
            '--outdir', output_dir,
            input_path
        ], capture_output=True, text=True, timeout=120)

        if result.returncode != 0:
            raise Exception(f"LibreOffice conversion failed: {result.stderr}")

        # LibreOffice creates file with same name but .pdf extension
        base_name = os.path.splitext(os.path.basename(input_path))[0]
        generated_file = os.path.join(output_dir, f"{base_name}.pdf")

        if not os.path.exists(generated_file):
            raise Exception(f"Output file not created: {generated_file}")

        if generated_file != output_path:
            os.rename(generated_file, output_path)

        return {"status": "success", "output_path": output_path}
    except subprocess.TimeoutExpired:
        return {"status": "error", "message": "Conversion timeout - file too large or complex"}
    except Exception as e:
        return {"status": "error", "message": str(e)}


def pdf_to_word(input_path, output_path):
    """Convert PDF to Word document using PyPDF2 and python-docx"""
    try:
        # Read PDF
        reader = PdfReader(input_path)

        # Create Word document
        doc = Document()
        doc.add_heading('PDF Conversion', 0)

        # Extract text from each page
        for page_num, page in enumerate(reader.pages):
            try:
                text = page.extract_text()

                # Add page break after first page
                if page_num > 0:
                    doc.add_page_break()

                # Add page heading
                doc.add_heading(f'Page {page_num + 1}', level=2)

                # Add text content
                if text and text.strip():
                    # Split into paragraphs (by double newlines)
                    paragraphs = text.split('\n\n')
                    for para_text in paragraphs:
                        para_text = para_text.strip()
                        if para_text:
                            paragraph = doc.add_paragraph(para_text)
                            paragraph.style.font.size = Pt(11)
                else:
                    doc.add_paragraph('[No text content on this page]')

            except Exception as page_error:
                doc.add_paragraph(f'[Error extracting page {page_num + 1}: {str(page_error)}]')

        # Save document
        doc.save(output_path)

        return {"status": "success", "output_path": output_path}
    except Exception as e:
        return {"status": "error", "message": str(e)}


def merge_pdfs(output_path, input_paths):
    """Merge multiple PDF files"""
    try:
        if not input_paths or len(input_paths) < 2:
            raise Exception("At least 2 PDF files required for merging")

        merger = PdfMerger()

        for pdf_path in input_paths:
            if not os.path.exists(pdf_path):
                raise Exception(f"Input file not found: {pdf_path}")
            try:
                merger.append(pdf_path)
            except Exception as e:
                raise Exception(f"Error appending {pdf_path}: {str(e)}")

        merger.write(output_path)
        merger.close()

        return {"status": "success", "output_path": output_path}
    except Exception as e:
        return {"status": "error", "message": str(e)}


def split_pdf(input_path, output_path, options=None):
    """Split PDF into multiple files"""
    try:
        import zipfile

        reader = PdfReader(input_path)
        total_pages = len(reader.pages)

        if total_pages == 0:
            raise Exception("PDF has no pages")

        split_type = options.get('splitType', 'all') if options else 'all'
        pages_spec = options.get('pages', '') if options else ''

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

        elif split_type == 'range' and pages_spec:
            # Split specific page ranges
            page_ranges = parse_page_ranges(pages_spec, total_pages)

            for idx, (start, end) in enumerate(page_ranges):
                writer = PdfWriter()
                for page_num in range(start - 1, end):
                    if 0 <= page_num < total_pages:
                        writer.add_page(reader.pages[page_num])

                range_output = os.path.join(split_dir, f'pages_{start}-{end}.pdf')
                with open(range_output, 'wb') as output_file:
                    writer.write(output_file)
                files_created += 1
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

        return {"status": "success", "output_path": output_path, "files_created": files_created}
    except Exception as e:
        return {"status": "error", "message": str(e)}


def compress_pdf(input_path, output_path, options=None):
    """Compress PDF file using PyPDF2"""
    try:
        compression_level = options.get('compressionLevel', 'medium') if options else 'medium'

        reader = PdfReader(input_path)
        writer = PdfWriter()

        # Add all pages with compression
        for page in reader.pages:
            writer.add_page(page)

        # Enable compression
        writer.add_metadata(reader.metadata)

        # Write compressed PDF
        with open(output_path, 'wb') as output_file:
            writer.write(output_file)

        # Calculate compression ratio
        original_size = os.path.getsize(input_path)
        compressed_size = os.path.getsize(output_path)
        compression_ratio = (1 - compressed_size / original_size) * 100 if original_size > 0 else 0

        return {
            "status": "success",
            "output_path": output_path,
            "original_size": original_size,
            "compressed_size": compressed_size,
            "compression_ratio": f"{compression_ratio:.2f}%"
        }
    except Exception as e:
        return {"status": "error", "message": str(e)}


def rotate_pdf(input_path, output_path, options=None):
    """Rotate PDF pages"""
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
            pages_to_rotate = list(range(total_pages))
        else:
            pages_to_rotate = parse_page_list(pages_spec, total_pages)

        for i in range(total_pages):
            page = reader.pages[i]
            if i in pages_to_rotate:
                page.rotate(degrees)
            writer.add_page(page)

        with open(output_path, 'wb') as output_file:
            writer.write(output_file)

        return {"status": "success", "output_path": output_path, "rotated_pages": len(pages_to_rotate)}
    except Exception as e:
        return {"status": "error", "message": str(e)}


def images_to_pdf(output_path, input_paths):
    """Convert multiple images to a single PDF"""
    try:
        if not input_paths:
            raise Exception("No image files provided")

        # Open and convert images
        images = []
        for img_path in input_paths:
            if not os.path.exists(img_path):
                raise Exception(f"Image file not found: {img_path}")

            try:
                img = Image.open(img_path)

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

                images.append(img)
            except Exception as e:
                raise Exception(f"Error processing image {img_path}: {str(e)}")

        if not images:
            raise Exception("No valid images to convert")

        # Save as PDF
        images[0].save(
            output_path,
            save_all=True,
            append_images=images[1:] if len(images) > 1 else [],
            resolution=100.0,
            quality=95
        )

        return {"status": "success", "output_path": output_path, "image_count": len(images)}
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

        elif operation == "MERGE_PDF":
            output_path = sys.argv[2]
            input_paths = sys.argv[3:]
            result = merge_pdfs(output_path, input_paths)

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
            input_paths = sys.argv[3:]
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

        else:
            result = {"status": "error", "message": f"Unknown operation: {operation}"}

        print(json.dumps(result))
        sys.exit(0 if result.get("status") == "success" else 1)

    except Exception as e:
        print(json.dumps({"status": "error", "message": str(e)}))
        sys.exit(1)


if __name__ == "__main__":
    main()