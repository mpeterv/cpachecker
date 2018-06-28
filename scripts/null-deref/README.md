# Инструкция по сбору аннотаций

## Зависимости

* Код ядра (4.2.6).
* CPAchecker: `https://github.com/mutilin/cpachecker`, ветка `muauto-match-deref`. Комадна сборки: `ant`.
* Kartographer: `https://forge.ispras.ru/projects/kartographer`.
* CIF.

## Патчи

### Kartographer

В Kartographer нужно пропатчить `kartographer.py`, чтобы присваивание `cif_args` выглядело так:

```python
cif_args = [cif,
            "--debug", "ALL",
            "--in", cif_in,
            "--aspect", aspect,
            "--back-end", "src",
            "--out", cif_out,
            "--keep"]
```

### Makefile ядра

В Makefile ядра необходимо закомментировать следующие строчки:

```make
ifeq ($(shell $(CONFIG_SHELL) $(srctree)/scripts/gcc-goto.sh $(CC)), y)
    KBUILD_CFLAGS += -DCC_HAVE_ASM_GOTO
    KBUILD_AFLAGS += -DCC_HAVE_ASM_GOTO
endif
```

CPAchecker не понимает `asm goto`, а CIF не избавляется от него.

## Шаги сбора аннотаций

### Запуск Kartographer

1. `make clean` и `make allmodconfig` в папке с исходниками ядра.
2. `python3 bce.py --sources <path to Linux sources`.
3. `python3 kartographer.py --bc cmds.json --cif <path to CIF binary>`.

Также см. инструкции в README-kartographer.txt.

В результате должны получиться файл `workdir/km.json` и папка `workdir/cif`, они используются в последующих шагах.

### Подготовка к запуску CPAchecker

1. `python3 scripts/null-deref/preplan.py <path to km.json> preplan.json`
2. `python3 scripts/null-deref/plan.py preplan.json plan.json`. Можно передать `--attempts=N`, чтобы `N` раз распределить функции по файлам и выбрать наилучший вариант.

### Запуск CPAchecker

Команда запуска: `python3 scripts/null-deref/run.py <path to cpachecker dir> <path to workdir/cif> plan.json annotations workdir`.

В `annotations` по мере работы складываются аннотации во внутреннем формате (по файлу на функцию).

В `workdir` пишутся временные файлы. В `workdir/log.txt` лог запусков, можно следить  при помощи `tail -f`. При перезапуске с существующей папкой `workdir` прогресс восстанавливается и запуски продолжаются с последнего файла. Можно начать с более раннего файла при помощи `--from-file=INDEX`.

`--generations=N` проходит по файлам `N` раз, переанализируя только функции с изменившимися аннотациями вызываемых функций.

`--heap` и `--time` задают лимиты, передаваемые в CPAchecker, например, `--heap 20000M --time 1800s`.

`--timeout` задаёт внешний timeout при запуске CPAchecker в секундах, например, `--timeout 2000`.

### Сбор аннотаций и генерация аспектов

1. `python3 scripts/null-deref/collect.py plan.json annotations annotations.json` собирает все аннотации в один JSON файл.
2. `python3 scripts/null-deref/aspects.py <path to km.json> annotations.json null_deref_assert.aspect`.

### Использование в Klever

1. Файлы `null_deref_assert.c`, `null_deref_assert.h` (из этого репозитория), и полученный `null_deref_assert.aspect` необходимо добавить в job Klever'а.
2. В `rule specs.json` в настройках RSG для используемого rule надо в `modeles` добавить `"null_deref_assert.c": {}`.
