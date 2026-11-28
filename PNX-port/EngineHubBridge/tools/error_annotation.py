"""Publish a short CI failure log as an annotation; no push token needed."""
import pathlib, sys
for filename in sys.argv[1:]:
    path = pathlib.Path(filename)
    if path.is_file():
        text = path.read_text(errors='replace')[-5500:]
        escaped = (path.name + '\n' + text).replace('%', '%25').replace('\r', '%0D').replace('\n', '%0A')
        print('::error::' + escaped)
