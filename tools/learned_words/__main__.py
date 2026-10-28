"""Explicit-file CLI only. No implicit Gmail/Outlook/WhatsApp scans or uploads."""
import argparse
import sys
from .codec import Entry, VocabularyError, merge, read, write_new

class PrivateParser(argparse.ArgumentParser):
    def error(self, message):
        # argparse's message can contain private filenames or unknown argument values.
        self.exit(2, "Arguments refused. Use --help for the accepted command syntax.\n")

def main():
    parser = PrivateParser(prog="lip-learned-words", description="Validate or merge portable Lip learned-word files locally.")
    commands = parser.add_subparsers(dest="command", required=True)
    verify = commands.add_parser("verify")
    verify.add_argument("file")
    combine = commands.add_parser("merge")
    combine.add_argument("existing")
    combine.add_argument("incoming")
    combine.add_argument("--output", required=True)
    args = parser.parse_args()
    try:
        if args.command == "verify":
            entries = read(args.file)
            print(f"Valid Lip vocabulary v1: {len(entries)} entries. Terms were not printed.")
        else:
            entries = merge(read(args.existing), read(args.incoming))
            write_new(args.output, entries)
            print(f"Created a new portable vocabulary with {len(entries)} entries. Existing files were preserved.")
        return 0
    except (VocabularyError, OSError):
        # OSError filenames and parser snippets may contain private words; do not echo them.
        print("Vocabulary operation refused. Check supported Unicode runtime, format, limits, permissions and that the output does not exist.", file=sys.stderr)
        return 2

if __name__ == "__main__":
    raise SystemExit(main())
