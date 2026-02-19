#!/usr/bin/env python3
"""
Universal Financial OCR Post-Processor
Handles: Currency symbols, numeric artifacts, tax IDs, table structure
Works: Globally for all currencies and languages
"""

import sys
import json
import re
from typing import Dict, List, Tuple, Optional
import unicodedata

class FinancialOCRPostProcessor:
    """
    Universal OCR cleaner for financial documents worldwide
    """

    # ========================================================================
    # CURRENCY SYMBOL MAPPING (Global)
    # ========================================================================
    CURRENCY_PATTERNS = {
        # Symbol → (Correct Unicode, Common OCR Mistakes)
        '₹': ['H', 'W', 'm', 'l', 'T', 'F', 'R', 'Rs', 'INR'],  # Indian Rupee
        '$': ['S', 'USD', 'US', 'Ss'],                           # US Dollar
        '€': ['EUR', 'E', 'C', '€'],                             # Euro
        '£': ['GBP', 'L', 'E', 'f'],                             # British Pound
        '¥': ['JPY', 'CNY', 'Y', 'V'],                           # Yen/Yuan
        'kr': ['SEK', 'NOK', 'DKK'],                             # Krona
        'R': ['ZAR', 'BRL'],                                     # Rand/Real
        'AED': ['AED', 'Dh', 'DH'],                              # UAE Dirham
        'SAR': ['SAR', 'SR'],                                    # Saudi Riyal
        'CHF': ['CHF', 'Fr'],                                    # Swiss Franc
        'CAD': ['CAD', 'C$'],                                    # Canadian Dollar
        'AUD': ['AUD', 'A$'],                                    # Australian Dollar
        'SGD': ['SGD', 'S$'],                                    # Singapore Dollar
    }

    # ========================================================================
    # TAX ID PATTERNS (Global)
    # ========================================================================
    TAX_ID_PATTERNS = {
        'GSTIN': r'\b\d{2}[A-Z]{5}\d{4}[A-Z]{1}[A-Z\d]{1}[Z]{1}[A-Z\d]{1}\b',  # India
        'VAT_EU': r'\b[A-Z]{2}\d{8,12}\b',                                       # EU VAT
        'EIN': r'\b\d{2}-\d{7}\b',                                               # US EIN
        'ABN': r'\b\d{2}\s?\d{3}\s?\d{3}\s?\d{3}\b',                            # Australia ABN
        'UTR': r'\b\d{10}\b',                                                    # UK UTR
        'TIN': r'\b[A-Z0-9]{9,15}\b',                                            # Generic TIN
    }

    def __init__(self):
        self.detected_currency = None
        self.detected_tax_system = None

    # ========================================================================
    # STEP 1: Auto-Detect Currency
    # ========================================================================

    def detect_currency(self, text: str) -> Optional[str]:
        """
        Auto-detect currency from document text
        Returns: Currency symbol (e.g., '₹', '$', '€')
        """
        # Count occurrences of currency patterns
        currency_scores = {}

        for symbol, patterns in self.CURRENCY_PATTERNS.items():
            score = 0
            for pattern in patterns:
                # Case-insensitive search
                score += len(re.findall(re.escape(pattern), text, re.IGNORECASE))

            if score > 0:
                currency_scores[symbol] = score

        # Return most frequent currency
        if currency_scores:
            detected = max(currency_scores, key=currency_scores.get)
            print(f"[OCR Post-Processor] Detected currency: {detected}", file=sys.stderr)
            return detected

        return None

    # ========================================================================
    # STEP 2: Fix Currency Symbols
    # ========================================================================

    def fix_currency_symbols(self, text: str, currency: str) -> str:
        """
        Replace OCR mistakes with correct currency symbol
        """
        if not currency:
            return text

        mistakes = self.CURRENCY_PATTERNS.get(currency, [])

        # Pattern: [MISTAKE_SYMBOL][OPTIONAL_SPACE][DIGITS]
        # Example: "H5,000.00" → "₹5,000.00"
        #          "m 2,000" → "₹2,000"

        for mistake in mistakes:
            # Match mistake followed by number
            pattern = rf'\b{re.escape(mistake)}\s?([\d,\.]+)\b'
            replacement = f'{currency}\\1'
            text = re.sub(pattern, replacement, text, flags=re.IGNORECASE)

        return text

    # ========================================================================
    # STEP 3: Clean Numeric Artifacts
    # ========================================================================

    def clean_numeric_artifacts(self, text: str) -> str:
        """
        Remove OCR artifacts from numbers
        Examples:
          - "l3,500.00" → "3,500.00"
          - "W25,000" → "25,000"
          - "5,000.00O" → "5,000.00"
        """
        # Pattern: [LETTER][DIGITS_WITH_SEPARATORS]
        # Remove leading/trailing letters from numbers

        # Leading letter removal
        text = re.sub(r'\b([a-zA-Z])([\d,\.]+)\b', r'\2', text)

        # Trailing letter removal (but keep valid suffixes like 'M', 'K', 'B')
        text = re.sub(r'\b([\d,\.]+)([a-zA-Z])(?!\w)',
                      lambda m: m.group(1) if m.group(2) not in ['M', 'K', 'B', 'm', 'k', 'b']
                      else m.group(0),
                      text)

        return text

    # ========================================================================
    # STEP 4: Fix Common OCR Character Mistakes
    # ========================================================================

    def fix_common_ocr_mistakes(self, text: str) -> str:
        """
        Fix common OCR character substitutions
        """
        replacements = {
            # Numbers
            'O': '0',  # Letter O → Zero (in numeric contexts)
            'l': '1',  # Lowercase L → One
            'I': '1',  # Uppercase i → One
            'S': '5',  # In specific contexts
            'B': '8',  # In specific contexts

            # Special characters
            '|': 'I',  # Pipe → Letter I
            '¢': 'c',  # Cent sign
        }

        # Only replace in numeric contexts (before/after digits)
        for wrong, correct in replacements.items():
            # Replace O with 0 only when surrounded by digits
            if wrong in ['O', 'l', 'I']:
                text = re.sub(rf'(\d){re.escape(wrong)}', rf'\g<1>{correct}', text)
                text = re.sub(rf'{re.escape(wrong)}(\d)', rf'{correct}\g<1>', text)

        return text

    # ========================================================================
    # STEP 5: Validate and Fix Tax IDs
    # ========================================================================

    def validate_tax_ids(self, text: str) -> Dict[str, List[str]]:
        """
        Find and validate tax identification numbers
        Returns: {tax_type: [found_ids]}
        """
        found_ids = {}

        for tax_type, pattern in self.TAX_ID_PATTERNS.items():
            matches = re.findall(pattern, text)
            if matches:
                found_ids[tax_type] = matches
                print(f"[OCR Post-Processor] Found {tax_type}: {matches}", file=sys.stderr)

        return found_ids

    def fix_tax_id_errors(self, text: str, tax_ids: Dict[str, List[str]]) -> str:
        """
        Apply corrections to known tax ID OCR errors
        """
        # Example: GSTIN often has "1Z5" → should be "1Z5" (correct) or "125" (wrong)
        # This requires business logic validation

        # For now, just normalize spacing
        for tax_type, ids in tax_ids.items():
            for tax_id in ids:
                # Remove internal spaces from tax IDs
                normalized = tax_id.replace(' ', '')
                text = text.replace(tax_id, normalized)

        return text

    # ========================================================================
    # STEP 6: Normalize Unicode (Multi-language)
    # ========================================================================

    def normalize_unicode(self, text: str) -> str:
        """
        Normalize Unicode characters for consistency
        Handles: Arabic, Chinese, Cyrillic, etc.
        """
        # NFKC normalization: compatibility decomposition, then canonical composition
        normalized = unicodedata.normalize('NFKC', text)
        return normalized

    # ========================================================================
    # STEP 7: Extract Structured Data
    # ========================================================================

    def extract_amounts(self, text: str) -> List[Tuple[str, float]]:
        """
        Extract monetary amounts with their context
        Returns: [(context, amount), ...]
        """
        # Pattern: Currency symbol followed by number
        currency_regex = r'([₹$€£¥₩₪₫฿₱₡₴₦₨₩₭₮₽₼₾])\s?([\d,]+\.?\d*)'

        amounts = []
        for match in re.finditer(currency_regex, text):
            currency_symbol = match.group(1)
            amount_str = match.group(2).replace(',', '')

            try:
                amount = float(amount_str)

                # Get context (20 chars before)
                start = max(0, match.start() - 20)
                context = text[start:match.start()].strip()

                amounts.append((context, amount))
            except ValueError:
                continue

        return amounts

    # ========================================================================
    # MAIN PROCESSING PIPELINE
    # ========================================================================

    def process(self, raw_ocr_text: str) -> Dict:
        """
        Main pipeline: Clean and structure OCR text
        """
        print("[OCR Post-Processor] Starting universal cleanup...", file=sys.stderr)

        # Step 1: Unicode normalization (multi-language)
        text = self.normalize_unicode(raw_ocr_text)

        # Step 2: Auto-detect currency
        self.detected_currency = self.detect_currency(text)

        # Step 3: Fix common OCR mistakes
        text = self.fix_common_ocr_mistakes(text)

        # Step 4: Fix currency symbols
        if self.detected_currency:
            text = self.fix_currency_symbols(text, self.detected_currency)

        # Step 5: Clean numeric artifacts
        text = self.clean_numeric_artifacts(text)

        # Step 6: Validate tax IDs
        tax_ids = self.validate_tax_ids(text)
        text = self.fix_tax_id_errors(text, tax_ids)

        # Step 7: Extract structured data
        amounts = self.extract_amounts(text)

        # Return cleaned text + metadata
        result = {
            "cleaned_text": text,
            "detected_currency": self.detected_currency,
            "detected_tax_ids": tax_ids,
            "extracted_amounts": [
                {"context": ctx, "amount": amt} for ctx, amt in amounts
            ],
            "original_length": len(raw_ocr_text),
            "cleaned_length": len(text),
            "processing_notes": []
        }

        print(f"[OCR Post-Processor] Cleanup complete. Currency: {self.detected_currency}", file=sys.stderr)

        return result


def main():
    """
    CLI interface for OCR post-processing
    Usage: python ocr_postprocessor.py <input_text_file> <output_json_file>
    """
    if len(sys.argv) != 3:
        print(json.dumps({
            "status": "error",
            "message": "Usage: ocr_postprocessor.py <input_text_file> <output_json_file>"
        }))
        sys.exit(1)

    input_file = sys.argv[1]
    output_file = sys.argv[2]

    try:
        # Read raw OCR text
        with open(input_file, 'r', encoding='utf-8') as f:
            raw_text = f.read()

        # Process
        processor = FinancialOCRPostProcessor()
        result = processor.process(raw_text)

        # Write result
        with open(output_file, 'w', encoding='utf-8') as f:
            json.dump(result, f, ensure_ascii=False, indent=2)

        print(json.dumps({"status": "success", "output": output_file}))
        sys.exit(0)

    except Exception as e:
        print(json.dumps({"status": "error", "message": str(e)}))
        sys.exit(1)


if __name__ == "__main__":
    main()