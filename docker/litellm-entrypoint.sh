#!/bin/sh
set -euo pipefail

TEMPLATE_PATH="/app/config.yaml"
RENDERED_PATH="/tmp/litellm.config.yaml"

python - "$TEMPLATE_PATH" "$RENDERED_PATH" <<'PY'
import os
import pathlib
import re
import sys

template_path, rendered_path = sys.argv[1], sys.argv[2]
template = pathlib.Path(template_path).read_text()

pattern = re.compile(r"\$\{([A-Za-z0-9_]+)(?::-(.*?))?\}")

def replace(match):
    var_name = match.group(1)
    default = match.group(2)
    value = os.environ.get(var_name)
    if value is None:
        value = default if default is not None else ""
    return value

rendered = pattern.sub(replace, template)
pathlib.Path(rendered_path).write_text(rendered)
PY

exec litellm --config "$RENDERED_PATH" --detailed_debug
