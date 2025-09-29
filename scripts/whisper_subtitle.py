import sys
import whisper
import json
import os
import io

class StdoutToStderr(io.StringIO):
    def write(self, text):
        sys.stderr.write(text)
        sys.stderr.flush()
    def flush(self):
        sys.stderr.flush()

def generate_subtitles(audio_path):
    try:
        print(f"Loading Whisper model, audio path: {audio_path}", file=sys.stderr)
        if not os.path.exists(audio_path):
            print(f"Audio file does not exist: {audio_path}", file=sys.stderr)
            sys.exit(1)
        model = whisper.load_model("base")
        print("Model loaded, starting transcription", file=sys.stderr)

        # Redirect stdout to stderr during transcription
        original_stdout = sys.stdout
        sys.stdout = StdoutToStderr()
        try:
            result = model.transcribe(audio_path, word_timestamps=False, verbose=False)
        finally:
            sys.stdout = original_stdout  # Restore stdout

        print("Transcription complete", file=sys.stderr)

        # Process segments to create subtitles with 2–3 words per chunk
        subtitles = []
        for segment in result["segments"]:
            text = segment["text"].strip()
            if not text or segment["end"] <= segment["start"]:
                continue

            # Split text into words
            words = text.split()
            if not words:
                continue

            # Calculate total duration of the segment
            start_time = max(0.0, segment["start"])
            end_time = segment["end"]
            duration = end_time - start_time

            # Create chunks of 2–3 words
            chunk_size = 3  # Prefer 3 words, fallback to 2 if necessary
            i = 0
            while i < len(words):
                # Determine number of words for this chunk (2 or 3)
                remaining_words = len(words) - i
                current_chunk_size = min(chunk_size, remaining_words)
                if current_chunk_size == 1 and i > 0:
                    # If only one word remains, append it to the previous chunk if possible
                    if subtitles and subtitles[-1]["end"] == start_time + (duration * i / len(words)):
                        subtitles[-1]["text"] += " " + words[i]
                        i += 1
                        continue
                elif current_chunk_size == 2 and remaining_words == 2:
                    current_chunk_size = 2  # Allow 2 words for the last chunk

                # Create chunk
                chunk_text = " ".join(words[i:i + current_chunk_size])
                if not chunk_text.strip():
                    i += current_chunk_size
                    continue

                # Calculate timing for this chunk
                chunk_start = start_time + (duration * i / len(words))
                chunk_end = start_time + (duration * (i + current_chunk_size) / len(words))
                if chunk_end <= chunk_start:
                    i += current_chunk_size
                    continue

                subtitles.append({
                    "start": chunk_start,
                    "end": chunk_end,
                    "text": chunk_text
                })
                i += current_chunk_size

        print(f"Generated {len(subtitles)} subtitles", file=sys.stderr)
        for i, subtitle in enumerate(subtitles):
            print(f"Subtitle {i+1}: start={subtitle['start']:.3f}, end={subtitle['end']:.3f}, text={subtitle['text']}", file=sys.stderr)
        return subtitles
    except Exception as e:
        print(f"Error during transcription: {str(e)}", file=sys.stderr)
        sys.exit(1)

if __name__ == "__main__":
    if len(sys.argv) != 2:
        print("Usage: python whisper_subtitle.py <audio_path>", file=sys.stderr)
        sys.exit(1)

    audio_path = sys.argv[1]
    subtitles = generate_subtitles(audio_path)
    print(json.dumps(subtitles))