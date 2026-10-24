#!/usr/bin/env python3
"""Старое имя аудитора — оставлено как обёртка.

Актуальная версия: tools/paperapi_audit.py (учтено наследование интерфейсов,
вложенные типы, enum-константы). Этот файл просто передаёт аргументы туда,
чтобы старые команды из документации продолжали работать.
"""
import os
import runpy
import sys

TARGET = os.path.join(os.path.dirname(os.path.abspath(__file__)), "paperapi_audit.py")
sys.argv[0] = TARGET
runpy.run_path(TARGET, run_name="__main__")
