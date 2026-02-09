from rembg import remove
from PIL import Image
import sys
import json

def process_image(input_path, output_path, max_dimension=None):
    try:
        input_img = Image.open(input_path)
        output_img = remove(input_img)

        # Resize if max_dimension is specified
        if max_dimension and max_dimension > 0:
            width, height = output_img.size
            max_current = max(width, height)

            if max_current > max_dimension:
                # Calculate new dimensions maintaining aspect ratio
                scale = max_dimension / max_current
                new_width = int(width * scale)
                new_height = int(height * scale)

                # Resize using high-quality Lanczos filter
                output_img = output_img.resize((new_width, new_height), Image.Resampling.LANCZOS)

        output_img.save(output_path, "PNG")

        final_width, final_height = output_img.size
        return {
            "status": "success",
            "output_path": output_path,
            "width": final_width,
            "height": final_height
        }
    except Exception as e:
        return {"status": "error", "message": str(e)}

if __name__ == "__main__":
    input_path = sys.argv[1]
    output_path = sys.argv[2]
    max_dimension = int(sys.argv[3]) if len(sys.argv) > 3 else None
    result = process_image(input_path, output_path, max_dimension)
    print(json.dumps(result))