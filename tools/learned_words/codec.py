"""Version1 vocabulary interchange. Private terms never appear in validation errors."""
from dataclasses import dataclass
import json
from itertools import chain
import os
from pathlib import Path
import tempfile
import unicodedata

FORMAT = "lip.learned-words"
VERSION = 1
UNICODE_VERSION = "16.0.0"
MAX_BYTES = 16 * 1024 * 1024
MAX_ENTRIES = 100_000
LANGUAGES = frozenset(("en", "ja", "zh", "es", "und"))
SOURCES = frozenset(("manual", "gmail", "outlook", "whatsapp", "import"))

class VocabularyError(ValueError):
    pass

def _require(ok, reason):
    if not ok:
        raise VocabularyError(reason)

def _unicode_contract():
    _require(unicodedata.unidata_version == UNICODE_VERSION, "Unsupported Unicode runtime; original file was not changed")

def _term(value):
    _unicode_contract()
    _require(type(value) is str, "Term must be text")
    _require(len(value) <= 256, "Input term length is outside bounds")
    value = unicodedata.normalize("NFC", value.strip())
    _require(1 <= len(value) <= 128, "Term length is outside bounds")
    try:
        _require(len(value.encode("utf-8")) <= 512, "Term byte length is outside bounds")
    except UnicodeError:
        raise VocabularyError("Invalid Unicode term") from None
    _require(not any(unicodedata.category(c).startswith("C") or c in "\r\n\t" for c in value), "Control characters are not allowed")
    _require(any(unicodedata.category(c).startswith("L") for c in value), "Term must contain a letter")
    _require("@" not in value and "://" not in value and not value.lower().startswith("www."), "Addresses and URLs require exclusion before import")
    return value

@dataclass(frozen=True)
class Entry:
    term: str
    language: str = "en"
    weight: int = 1
    source: str = "manual"

    def __post_init__(self):
        object.__setattr__(self, "term", _term(self.term))
        _require(type(self.language) is str and self.language in LANGUAGES, "Unsupported vocabulary language")
        _require(type(self.weight) is int and 1 <= self.weight <= 1_000_000, "Invalid vocabulary weight")
        _require(type(self.source) is str and self.source in SOURCES, "Unsupported source category")

    @property
    def identity(self):
        # Unicode NFC + full casefold is the explicit cross-platform identity contract.
        return (self.language, unicodedata.normalize("NFC", self.term.casefold()))

def _unique_object(pairs):
    result = {}
    for key, value in pairs:
        _require(key not in result, "Duplicate JSON property")
        result[key] = value
    return result

def _reject_constant(_):
    raise VocabularyError("Non-finite JSON number")

def decode(data):
    _require(type(data) is bytes and len(data) <= MAX_BYTES, "Vocabulary file exceeds size limit")
    _unicode_contract()
    try:
        obj = json.loads(data.decode("utf-8"), object_pairs_hook=_unique_object, parse_constant=_reject_constant)
    except (UnicodeError, ValueError, RecursionError, OverflowError):
        raise VocabularyError("Invalid vocabulary JSON") from None
    _require(type(obj) is dict and set(obj) == {"format", "version", "unicode_version", "entries"}, "Unknown vocabulary document fields")
    _require(obj["format"] == FORMAT and type(obj["version"]) is int and obj["version"] == VERSION, "Unsupported vocabulary format/version")
    _require(obj["unicode_version"] == UNICODE_VERSION, "Unsupported vocabulary Unicode contract")
    _require(type(obj["entries"]) is list and len(obj["entries"]) <= MAX_ENTRIES, "Vocabulary entry count exceeds limit")
    result = []
    seen = set()
    for row in obj["entries"]:
        _require(type(row) is dict and set(row) == {"term", "language", "weight", "source"}, "Invalid vocabulary entry fields")
        entry = Entry(**row)
        _require(entry.identity not in seen, "Duplicate normalized vocabulary entry")
        seen.add(entry.identity)
        result.append(entry)
    return tuple(result)

def merge(existing, incoming):
    """Preserve existing approved spelling; take max weight, never sum reimports."""
    _unicode_contract()
    rows = {}
    for index, entry in enumerate(chain(existing, incoming)):
        _require(index < MAX_ENTRIES * 2, "Vocabulary merge work exceeds limit")
        _require(type(entry) is Entry, "Expected vocabulary entries")
        previous = rows.get(entry.identity)
        if previous is None:
            rows[entry.identity] = entry
        elif entry.weight > previous.weight:
            rows[entry.identity] = Entry(previous.term, previous.language, entry.weight, previous.source)
        _require(len(rows) <= MAX_ENTRIES, "Merged vocabulary exceeds limit")
    return tuple(rows[key] for key in sorted(rows))

def encode(entries):
    rows = merge((), entries)
    obj = {"format": FORMAT, "version": VERSION, "unicode_version": UNICODE_VERSION, "entries": [dict(term=e.term, language=e.language, weight=e.weight, source=e.source) for e in rows]}
    result = (json.dumps(obj, ensure_ascii=False, indent=2, allow_nan=False) + "\n").encode("utf-8")
    _require(len(result) <= MAX_BYTES, "Encoded vocabulary exceeds size limit")
    return result

def read(path):
    with Path(path).open("rb") as stream:
        return decode(stream.read(MAX_BYTES + 1))

def write_new(path, entries):
    """Publish a fully written new file atomically. Never overwrite a backup or symlink."""
    path = Path(path)
    data = encode(entries)
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(prefix=".lip-vocabulary-", dir=path.parent, delete=False) as stream:
            temporary = Path(stream.name)
            os.chmod(temporary, 0o600)
            stream.write(data)
            stream.flush()
            os.fsync(stream.fileno())
        # Hard-link publication is exclusive even if the destination races into existence.
        os.link(temporary, path)
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)
