"""Portable, local-only vocabulary contract; no inbox/database access or model training."""
from .codec import Entry, VocabularyError, decode, encode, merge, read, write_new
