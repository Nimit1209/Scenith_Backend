import whisper
import sys
import json
import argparse
import logging
import warnings

# Suppress Whisper warnings
warnings.filterwarnings("ignore")

# Suppress logging output
logging.getLogger().setLevel(logging.CRITICAL)

def transcribe_audio(input_path, output_format):
    try:
        # Load model
        model = whisper.load_model("base")  # Use 'small' or 'medium' for better accuracy

        # Transcribe
        result = model.transcribe(input_path, verbose=False)

        # Extract segments
        segments = [
            {
                "start": segment["start"],
                "end": segment["end"],
                "text": segment["text"].strip()
            } for segment in result["segments"]
        ]

        # Output based on format
        if output_format == "json":
            print(json.dumps(segments))
        else:
            for segment in segments:
                print(f"{segment['start']} - {segment['end']}: {segment['text']}")
    except Exception as e:
        print(f"Error during transcription: {str(e)}", file=sys.stderr)
        sys.exit(1)

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", required=True, help="Path to audio file")
    parser.add_argument("--output_format", default="json", help="Output format: json or text")
    args = parser.parse_args()
    transcribe_audio(args.input, args.output_format)