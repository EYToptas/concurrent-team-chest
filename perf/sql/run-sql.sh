#!/bin/sh
# Runs a SQL file as MySQL root; name=value arguments become session variables.
set -eu

if [ $# -lt 1 ]; then
  echo "usage: run-sql.sh <file.sql> [name=value ...]" >&2
  exit 2
fi
file="$1"
shift
case "$file" in
  *[!A-Za-z0-9._-]* | .* | "") echo "invalid file name: $file" >&2; exit 2 ;;
esac
if [ ! -f "/sql/$file" ]; then
  echo "no such file: /sql/$file" >&2
  exit 2
fi

variables=""
for argument in "$@"; do
  name="${argument%%=*}"
  value="${argument#*=}"
  case "$name" in
    "" | *[!a-z_]*) echo "invalid variable name: $name" >&2; exit 2 ;;
  esac
  case "$value" in
    "" | *[!0-9]*) echo "$name must be a non-negative integer: $value" >&2; exit 2 ;;
  esac
  variables="$variables SET @$name = $value;"
done

export MYSQL_PWD="${MYSQL_PWD:-${MYSQL_ROOT_PASSWORD:-}}"
exec mysql --host=mysql --user=root --batch --skip-column-names \
  --execute="$variables SOURCE /sql/$file;"
