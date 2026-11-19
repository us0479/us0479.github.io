"""Copy canonical UI and sample files; keep SQL initialization in db/."""

from pathlib import Path
import shutil


def main():
    root = Path(__file__).resolve().parents[1]
    resources = root / "src/main/resources"
    copies = (
        (root / "dist/app.js", resources / "static/app.js"),
        (root / "dist/app.css", resources / "static/app.css"),
        (root / "dist/index.html", resources / "templates/index.html"),
        (root / "samples/events-r02.csv", resources / "static/samples/events-r02.csv"),
        (root / "samples/events-invalid-customer.csv",
         resources / "static/samples/events-invalid-customer.csv"),
        (root / "samples/events-boundaries.csv",
         resources / "static/samples/events-boundaries.csv"),
    )
    # Check every input before creating directories or updating existing copies.
    missing = [str(source.relative_to(root)) for source, _ in copies if not source.is_file()]
    if missing:
        raise SystemExit("Missing asset sources: " + ", ".join(missing))
    for source, destination in copies:
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, destination)
    # Existing generated files are preserved; stale-file removal is a separate step.
    print(f"Synchronized {len(copies)} UI/CSV files; SQL and other existing files unchanged.")


if __name__ == "__main__":
    main()
