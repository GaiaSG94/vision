#!/usr/bin/env python3
# Garde-fou : fichiers .kt/.kts < 500 lignes, fonctions/composables < 50 lignes.
# Heuristique par accolades équilibrées (commentaires et chaînes retirés d'abord).
# Usage : python3 tools/check-kotlin-limits.py [racine]   (sortie != 0 si dépassement)
import os
import re
import sys

MAX_FILE_LINES = 500
MAX_FUN_LINES = 50
SKIP_DIRS = {".git", ".gradle", "build", ".idea", "tmp", "node_modules"}
FUN_RE = re.compile(r"\bfun\s+(?:<[^>]*>\s*)?(?:[\w.]+\s*\.\s*)?`?([A-Za-z_]\w*)`?\s*(?:\(|<)")


def strip_strings_comments(lines):
    """Remplace le contenu des chaînes et commentaires par des espaces.

    Retourne une liste de lignes « code pur » de même longueur, pour que
    le comptage des accolades ne voie jamais de { ou } littéral.
    """
    out = []
    state = None          # None | 'line' | 'block' | 'str' | 'char' | 'triple'
    for raw in lines:
        line, i, n = raw, 0, len(raw)
        while i < n:
            two, three = raw[i:i + 2], raw[i:i + 3]
            if state is None:
                if two == "//":
                    state, line = "line", line[:i] + " " * (n - i)
                    break
                if two == "/*":
                    state = "block"
                    line = line[:i] + "  " + line[i + 2:]
                    i += 2
                    continue
                if three == '"""':
                    state = "triple"
                    line = line[:i] + "   " + line[i + 3:]
                    i += 3
                    continue
                if raw[i] == '"':
                    state = "str"
                    i += 1
                    continue
                if raw[i] == "'":
                    state = "char"
                    i += 1
                    continue
                i += 1
            elif state == "block":
                if two == "*/":
                    state = None
                    line = line[:i] + "  " + line[i + 2:]
                    i += 2
                else:
                    line = line[:i] + " " + line[i + 1:]
                    i += 1
            elif state == "triple":
                if three == '"""':
                    state = None
                    line = line[:i] + "   " + line[i + 3:]
                    i += 3
                else:
                    line = line[:i] + " " + line[i + 1:]
                    i += 1
            elif state in ("str", "char"):
                q = '"' if state == "str" else "'"
                if raw[i] == "\\":
                    line = line[:i] + "  " + line[i + 2:]
                    i += 2
                    continue
                if raw[i] == q:
                    state = None
                line = line[:i] + " " + line[i + 1:]
                i += 1
        if state == "line":
            state = None
        out.append(line)
    return out


def indent_of(code, i):
    return len(code[i]) - len(code[i].lstrip())


def fun_spans(code):
    """Rend (début0, fin0, nom) pour chaque fun déclarée (heuristique)."""
    spans, i, n = [], 0, len(code)
    while i < n:
        m = FUN_RE.search(code[i])
        if not m:
            i += 1
            continue
        name, start, base_indent = m.group(1), i, indent_of(code, i)
        # 1) signature : chercher le « { » du corps hors des parenthèses.
        j, parens, body_open, body_col = i, 0, None, 0
        while j < n:
            for c in range(m.end() if j == i else 0, len(code[j])):
                ch = code[j][c]
                if ch == "(":
                    parens += 1
                elif ch == ")":
                    parens -= 1
                elif ch == "{" and parens <= 0:
                    body_open, body_col = j, c
                    break
            if body_open is not None:
                break
            if j > i and code[j].strip() and indent_of(code, j) <= base_indent:
                break                                   # autre déclaration : pas de corps
            j += 1
        if body_open is None:
            spans.append((start, max(start, j - 1), name))
            i = start + 1
            continue
        # 2) corps : équilibrer { } à partir de body_open/body_col.
        depth, k, end = 0, body_open, None
        while k < n:
            frag = code[k][body_col:] if k == body_open else code[k]
            depth += frag.count("{") - frag.count("}")
            if depth <= 0:
                end = k
                break
            k += 1
        spans.append((start, end if end is not None else n - 1, name))
        i = start + 1
    return spans


def check_file(path):
    problems = []
    with open(path, encoding="utf-8", errors="replace") as fh:
        raw = fh.read().splitlines()
    if len(raw) >= MAX_FILE_LINES:
        problems.append((1, f"fichier de {len(raw)} lignes (max {MAX_FILE_LINES - 1})"))
    code = strip_strings_comments(raw)
    for start, end, name in fun_spans(code):
        size = end - start + 1
        if size >= MAX_FUN_LINES:
            kind = "composable" if name[:1].isupper() else "fun"
            problems.append((start + 1, f"{kind} {name}() : {size} lignes (max {MAX_FUN_LINES - 1})"))
    return problems


def main():
    root = sys.argv[1] if len(sys.argv) > 1 else "."
    total, bad_files = 0, []
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = sorted(d for d in dirnames if d not in SKIP_DIRS)
        for fn in sorted(filenames):
            if fn.endswith((".kt", ".kts")):
                path = os.path.join(dirpath, fn)
                total += 1
                problems = check_file(path)
                if problems:
                    bad_files.append((path, problems))
    print(f"check-kotlin-limits : {total} fichier(s) .kt/.kts inspecté(s)")
    print(f"règles : fichier < {MAX_FILE_LINES} lignes, fonction/composable < {MAX_FUN_LINES} lignes")
    if not bad_files:
        print("OK — aucun dépassement.")
        return 0
    print(f"\n{sum(len(p) for _, p in bad_files)} dépassement(s) dans {len(bad_files)} fichier(s) :")
    for path, problems in bad_files:
        for line, msg in problems:
            print(f"  {path}:{line}: {msg}")
    return 1


if __name__ == "__main__":
    sys.exit(main())
