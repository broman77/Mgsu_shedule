from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected exactly one match, got {count}")
    return text.replace(old, new, 1)

repo = Path("app/src/main/java/ru/mgsu/schedule/data/MgsuRepository.kt")
s = repo.read_text()
s = replace_once(
    s,
    "val oldPaths = sources.map(MgsuSourceRules::canonicalPath).toSet()",
    "val oldPaths = sources.map { MgsuSourceRules.canonicalPath(it.url) }.toSet()",
    "source path mapping",
)
repo.write_text(s)

catalog = Path("app/src/main/java/ru/mgsu/schedule/data/MgsuCatalogRepository.kt")
s = catalog.read_text()
s = replace_once(
    s,
    """        // Prefer ordinary lesson/exam timetables; practice documents are only a last resort.\n        val preferred = sources.filterNot { MgsuSourceRules.isPracticeSource(it.url, it.label) }\n        val candidates = (preferred + sources).distinctBy { MgsuSourceRules.canonicalPath(it.url) }\n\n        for (source in candidates.take(MAX_GROUP_SCAN_PDFS)) {\n""",
    """        // Prefer ordinary lesson/exam timetables; practice documents are only a last resort.\n        // Do not stop after the first PDF: MGSU can split one course into a main file plus\n        // separate group ranges (for example 40-43/50/51/80/81). We still stay scoped to the\n        // selected institute/course, so this remains far smaller than the old 260-PDF scan.\n        val preferred = sources.filterNot { MgsuSourceRules.isPracticeSource(it.url, it.label) }\n            .distinctBy { MgsuSourceRules.canonicalPath(it.url) }\n        val practice = sources.filter { MgsuSourceRules.isPracticeSource(it.url, it.label) }\n            .distinctBy { MgsuSourceRules.canonicalPath(it.url) }\n        val candidates = if (preferred.isNotEmpty()) preferred else practice\n\n        for (source in candidates.take(MAX_GROUP_SCAN_PDFS)) {\n""",
    "course candidate selection",
)
s = replace_once(
    s,
    """\n            // One normal course PDF usually contains every group for that institute/course. Once\n            // we have a healthy set, avoid downloading session/practice duplicates unnecessarily.\n            if (foundGroups.size >= MIN_HEALTHY_GROUP_SET && readablePdfs > 0) break\n""",
    "\n",
    "remove premature course scan break",
)
s = s.replace("        private const val MIN_HEALTHY_GROUP_SET = 2\n", "")
catalog.write_text(s)

print("Compile fix and complete course scan patch applied")
