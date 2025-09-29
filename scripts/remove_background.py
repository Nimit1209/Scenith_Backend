from rembg import remove
from PIL import Image
import sys
import json

def process_image(input_path, output_path):
    try:
        input_img = Image.open(input_path)
        output_img = remove(input_img)
        output_img.save(output_path, "PNG")
        return {"status": "success", "output_path": output_path}
    except Exception as e:
        return {"status": "error", "message": str(e)}

if __name__ == "__main__":
    input_path = sys.argv[1]
    output_path = sys.argv[2]
    result = process_image(input_path, output_path)
    print(json.dumps(result))