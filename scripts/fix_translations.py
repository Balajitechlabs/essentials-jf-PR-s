#!/usr/bin/env python3
"""
Automated Translation Fixer for Essentials
Fixes all 67 known string formatting warnings across 74 translation files:
1. Serbian (sr, sr-rSP): Cyrillic 'ф' in format specifiers -> Latin 'f'
2. Catalan (ca, ca-rES) & Portuguese (pt, pt-rBR): Comma in format specifier '%1,1f' -> '%1$.1f'
3. Vietnamese (vi): 'RAM' -> '%1$d m'
4. Traditional Chinese (zh-rTW): Duplicated '%1$s' prefix in shizuku_status_prefix
5. Chinese (zh, zh-rTW): Missing percentage argument in location_reached_service_remaining
6. 55 translation files: location_reached_service_title missing '%1$s'
"""

import os
import re
import glob

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES_DIR = os.path.join(REPO_ROOT, "app", "src", "main", "res")

SERVICE_TITLE_TRANSLATIONS = {
    "ach": "Wot i %1$s",
    "ach-rUG": "Wot i %1$s",
    "af": "Op reis na %1$s",
    "af-rZA": "Op reis na %1$s",
    "ar-rSA": "السفر إلى %1$s",
    "ca": "Viatjant a %1$s",
    "ca-rES": "Viatjant a %1$s",
    "cs": "Cesta do %1$s",
    "cs-rCZ": "Cesta do %1$s",
    "da": "Rejser til %1$s",
    "da-rDK": "Rejser til %1$s",
    "de": "Reise nach %1$s",
    "de-rDE": "Reise nach %1$s",
    "el": "Ταξίδι προς %1$s",
    "el-rGR": "Ταξίδι προς %1$s",
    "en": "Travelling to %1$s",
    "en-rUS": "Travelling to %1$s",
    "es": "Viajando a %1$s",
    "es-rES": "Viajando a %1$s",
    "fi": "Matkalla kohteeseen %1$s",
    "fi-rFI": "Matkalla kohteeseen %1$s",
    "fr": "Trajet vers %1$s",
    "he": "נוסע אל %1$s",
    "iw-rIL": "נוסע אל %1$s",
    "hu": "Utazás ide: %1$s",
    "hu-rHU": "Utazás ide: %1$s",
    "it": "In viaggio verso %1$s",
    "ja": "%1$s へ移動中",
    "ja-rJP": "%1$s へ移動中",
    "ko": "%1$s(으)로 이동 중",
    "ko-rKR": "%1$s(으)로 이동 중",
    "nl": "Onderweg naar %1$s",
    "nl-rNL": "Onderweg naar %1$s",
    "no": "Reiser til %1$s",
    "no-rNO": "Reiser til %1$s",
    "pl": "Podróż do %1$s",
    "pl-rPL": "Podróż do %1$s",
    "pt": "Viajando para %1$s",
    "pt-rBR": "Viajando para %1$s",
    "ro": "Călătorie spre %1$s",
    "ro-rRO": "Călătorie spre %1$s",
    "ru": "Поездка в %1$s",
    "ru-rRU": "Поездка в %1$s",
    "si": "%1$s වෙත ගමන් කරමින්",
    "si-rLK": "%1$s වෙත ගමන් කරමින්",
    "sr": "Путовање до %1$s",
    "sr-rSP": "Путовање до %1$s",
    "sv": "Reser till %1$s",
    "sv-rSE": "Reser till %1$s",
    "uk": "Подорож до %1$s",
    "uk-rUA": "Подорож до %1$s",
    "vi": "Đang di chuyển đến %1$s",
    "vi-rVN": "Đang di chuyển đến %1$s",
    "zh": "正在前往 %1$s",
    "zh-rTW": "正在前往 %1$s",
}

def replace_string_in_file(file_path, key, new_val):
    if not os.path.exists(file_path):
        return False
    with open(file_path, "r", encoding="utf-8") as f:
        content = f.read()

    # Pattern matches <string name="key"[^>]*>.*?</string>
    pattern = re.compile(rf'(<string\s+name="{re.escape(key)}"[^>]*>)(.*?)(</string>)', re.DOTALL)
    match = pattern.search(content)
    if not match:
        return False

    old_val = match.group(2)
    if old_val == new_val:
        return False

    new_content = pattern.sub(rf'\g<1>{new_val}\g<3>', content)
    with open(file_path, "w", encoding="utf-8") as f:
        f.write(new_content)
    return True

def fix_all():
    fixed_count = 0
    files = sorted(glob.glob(os.path.join(RES_DIR, "values-*", "strings.xml")))

    for file_path in files:
        dir_name = os.path.basename(os.path.dirname(file_path))
        locale = dir_name.replace("values-", "")

        # 1. Serbian Cyrillic fixes
        if locale in ("sr", "sr-rSP"):
            with open(file_path, "r", encoding="utf-8") as f:
                c = f.read()
            if "%1$.1ф" in c or "%1$.4ф" in c or "%2$.4ф" in c:
                c = c.replace("%1$.1ф", "%1$.1f")
                c = c.replace("%1$.4ф", "%1$.4f")
                c = c.replace("%2$.4ф", "%2$.4f")
                with open(file_path, "w", encoding="utf-8") as f:
                    f.write(c)
                print(f"[{locale}] Fixed Cyrillic format specifiers in {file_path}")
                fixed_count += 1

        # 2. Catalan and Portuguese comma format specifier
        if locale in ("ca", "ca-rES", "pt", "pt-rBR"):
            with open(file_path, "r", encoding="utf-8") as f:
                c = f.read()
            if "%1$,1f" in c:
                c = c.replace("%1$,1f", "%1$.1f")
                with open(file_path, "w", encoding="utf-8") as f:
                    f.write(c)
                print(f"[{locale}] Fixed comma format specifier in {file_path}")
                fixed_count += 1

        # 3. Vietnamese dist_m
        if locale == "vi":
            if replace_string_in_file(file_path, "location_reached_dist_m", "%1$d m"):
                print(f"[{locale}] Fixed location_reached_dist_m in {file_path}")
                fixed_count += 1

        # 4. Traditional Chinese shizuku_status_prefix
        if locale == "zh-rTW":
            with open(file_path, "r", encoding="utf-8") as f:
                c = f.read()
            if "%1$s狀態： %1$s" in c:
                c = c.replace("%1$s狀態： %1$s", "狀態：%1$s")
                with open(file_path, "w", encoding="utf-8") as f:
                    f.write(c)
                print(f"[{locale}] Fixed duplicated placeholder in shizuku_status_prefix")
                fixed_count += 1

        # 5. Chinese location_reached_service_remaining
        if locale == "zh":
            if replace_string_in_file(file_path, "location_reached_service_remaining", "剩余 %1$s (%2$d%%)"):
                print(f"[{locale}] Fixed location_reached_service_remaining")
                fixed_count += 1
        elif locale == "zh-rTW":
            if replace_string_in_file(file_path, "location_reached_service_remaining", "剩餘 %1$s (%2$d%%)"):
                print(f"[{locale}] Fixed location_reached_service_remaining")
                fixed_count += 1

        # 6. location_reached_service_title in 55 languages
        if locale in SERVICE_TITLE_TRANSLATIONS:
            new_title = SERVICE_TITLE_TRANSLATIONS[locale]
            if replace_string_in_file(file_path, "location_reached_service_title", new_title):
                print(f"[{locale}] Updated location_reached_service_title -> {new_title}")
                fixed_count += 1

    print(f"\nTotal fixes applied: {fixed_count}")

if __name__ == "__main__":
    fix_all()
