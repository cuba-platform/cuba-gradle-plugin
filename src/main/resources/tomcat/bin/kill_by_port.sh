#!/bin/sh
kill `lsof -i :$1 | tail -n +2 | sed -e 's,[ \t][ \t]*, ,g' | cut -f2 -d' '`
