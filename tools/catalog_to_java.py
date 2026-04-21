#!/usr/bin/env python3
"""
Convert the output of extract_catalog.py into Java source for MigratedPropertiesCatalog.java.

Usage:
    python3 extract_catalog.py /path/to/gradle2 | python3 catalog_to_java.py > MigratedPropertiesCatalog.java
"""
import sys
from collections import defaultdict


KIND_TO_HELPER = {
    "SCALAR_PROPERTY": "scalar",
    "LIST_PROPERTY": "listLike",
    "SET_PROPERTY": "setLike",
    "MAP_PROPERTY": "mapLike",
    "CONFIGURABLE_FILE_COLLECTION": "configurableFileCollection",
    "DIRECTORY_PROPERTY": "directory",
    "REGULAR_FILE_PROPERTY": "regularFile",
}


def main():
    # Group entries: declaringType -> kind -> [propNames]
    grouped = defaultdict(lambda: defaultdict(list))
    for line in sys.stdin:
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        parts = line.split("|")
        if len(parts) < 3:
            continue
        fqn, prop, kind = parts[0], parts[1], parts[2]
        grouped[fqn][kind].append(prop)

    print("""package org.gradle.rewrite.providerapi.internal;

import java.util.Map;

/**
 * Auto-generated catalog of Gradle properties migrated to the Provider API.
 *
 * <p>Generated from the {@code gradle10/provider-api-migration} branch by scanning
 * {@code @ReplacesEagerProperty} annotations. Regenerate with
 * {@code tools/extract_catalog.py | tools/catalog_to_java.py}.
 *
 * <p>DO NOT EDIT BY HAND — hand-curated additions go in {@link MigratedProperties} directly.
 */
final class MigratedPropertiesCatalog {

    static void populate(CatalogSink sink) {""")
    for fqn in sorted(grouped):
        for kind in sorted(grouped[fqn]):
            props = sorted(set(grouped[fqn][kind]))
            helper = KIND_TO_HELPER[kind]
            # Chunk long lists to keep lines readable.
            args = ", ".join(f'"{p}"' for p in props)
            print(f'        sink.put("{fqn}", sink.{helper}({args}));')
    print("""    }

    interface CatalogSink {
        void put(String declaringType, Map<String, MigratedProperties.Kind> entries);
        Map<String, MigratedProperties.Kind> scalar(String... names);
        Map<String, MigratedProperties.Kind> listLike(String... names);
        Map<String, MigratedProperties.Kind> setLike(String... names);
        Map<String, MigratedProperties.Kind> mapLike(String... names);
        Map<String, MigratedProperties.Kind> configurableFileCollection(String... names);
        Map<String, MigratedProperties.Kind> directory(String... names);
        Map<String, MigratedProperties.Kind> regularFile(String... names);
    }

    private MigratedPropertiesCatalog() {}
}""")


if __name__ == "__main__":
    main()
