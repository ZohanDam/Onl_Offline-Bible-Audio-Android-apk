"""Import public David Suchet NIVUK chapter links, not bundled recordings."""
import argparse
import concurrent.futures
import json
import re
import shutil
import sqlite3
import time
import urllib.error
import urllib.request
from contextlib import closing
from pathlib import Path
from urllib.parse import urlparse

from bs4 import BeautifulSoup

ROOT = Path(__file__).resolve().parents[1]
BOOKS = "Gen Exod Lev Num Deut Josh Judg Ruth 1Sam 2Sam 1Kgs 2Kgs 1Chr 2Chr Ezra Neh Esth Job Ps Prov Eccl Song Isa Jer Lam Ezek Dan Hos Joel Amos Obad Jonah Mic Nah Hab Zeph Hag Zech Mal Matt Mark Luke John Acts Rom 1Cor 2Cor Gal Eph Phil Col 1Thess 2Thess 1Tim 2Tim Titus Phlm Heb Jas 1Pet 2Pet 1John 2John 3John Jude Rev".split()


def validate_url(url, book, chapter):
    parsed = urlparse(url)
    expected = rf"/bibles/32/nivuk-suchet/{re.escape(book)}\.{chapter}\.[a-f0-9]{{32}}\.mp3"
    if (parsed.scheme != "https" or parsed.netloc != "stream.biblegateway.com"
            or parsed.query or parsed.fragment or not re.fullmatch(expected, parsed.path)):
        raise ValueError(f"Not the requested Suchet NIVUK chapter: {book} {chapter}")
    return url


def extract_audio(document, book, chapter):
    soup = BeautifulSoup(document, "html.parser")
    selected = soup.select_one('select[name="recording"] option[selected]')
    # Validate the player identity as well as the stream path, never use a default recording blindly.
    if selected is None or selected.get("value") != "nivuk-suchet":
        raise ValueError("Player is not NIVUK by David Suchet")
    urls = [node.get("src", "") for node in soup.select("audio[src], audio source[src]")]
    if len(urls) != 1:
        raise ValueError("Expected one audio source")
    return validate_url(urls[0], book, chapter)


def request(url, method="GET", headers=None):
    for attempt in range(3):
        try:
            return urllib.request.urlopen(urllib.request.Request(url, method=method, headers=headers or {}), timeout=40)
        except urllib.error.HTTPError as error:
            if error.code in (401, 403, 404) or attempt == 2:
                raise
            time.sleep(2 ** attempt)
        except (TimeoutError, urllib.error.URLError):
            if attempt == 2:
                raise
            time.sleep(2 ** attempt)


def fetch(row, cache):
    order, code, number, book_order = row
    book = BOOKS[book_order - 1]
    target = cache / f"{order:04d}_{code}_{number:03d}.json"
    source = f"https://www.biblegateway.com/audio/suchet/nivuk/{book}.{number}"
    if target.exists():
        item = json.loads(target.read_text(encoding="utf-8"))
        if item.get("order") != order or item.get("source") != source:
            raise ValueError(f"Cache identity mismatch: {target}")
        validate_url(item["url"], book, number)
        if item.get("bytes", 0) <= 1024:
            raise ValueError("Cached size missing")
        return item
    with request(source) as response:
        url = extract_audio(response.read().decode("utf-8"), book, number)
    with request(url, "HEAD") as response:
        size = int(response.headers.get("Content-Length", 0))
        if response.status != 200 or size <= 1024 or response.headers.get_content_type() != "audio/mpeg":
            raise ValueError(f"Invalid recording: {source}")
    item = {"order": order, "book": code, "chapter": number, "url": url, "source": source, "bytes": size}
    target.write_text(json.dumps(item), encoding="utf-8")
    return item


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--database", type=Path, default=ROOT / "app/src/main/assets/bible.db")
    parser.add_argument("--cache", type=Path, default=ROOT / "archive/nivuk-audio-cache")
    parser.add_argument("--workers", type=int, default=4)
    parser.add_argument("--sample", type=int, default=0)
    args = parser.parse_args()
    args.cache.mkdir(parents=True, exist_ok=True)
    with closing(sqlite3.connect(args.database)) as db:
        rows = db.execute("SELECT c.global_order,c.book_code,c.chapter_number,b.book_order FROM chapters c JOIN books b ON b.code=c.book_code ORDER BY c.global_order").fetchall()
        original = {table: db.execute(f"SELECT * FROM {table} ORDER BY rowid").fetchall()
                    for table in ("books", "chapters", "verses", "text_versions", "version_books", "version_verses", "audio_chapters")}
    if len(rows) != 1189 or len(BOOKS) != 66:
        raise ValueError("Incomplete canonical inventory")
    if args.sample:
        rows = rows[:args.sample]
    results = []
    with concurrent.futures.ThreadPoolExecutor(max_workers=max(1, min(args.workers, 8))) as pool:
        for item in pool.map(lambda row: fetch(row, args.cache), rows):
            results.append(item)
            if len(results) % 50 == 0 or len(results) == len(rows):
                print(f"NIVUK Suchet metadata and HEAD: {len(results)}/{len(rows)}", flush=True)
    if args.sample:
        print("Sample verified; database unchanged", flush=True)
        return
    if len({item["url"] for item in results}) != 1189:
        raise ValueError("Duplicate recording links")
    for item in (results[0], results[928], results[-1]):
        with request(item["url"], headers={"Range": "bytes=0-1023"}) as response:
            if response.status not in (200, 206) or len(response.read(1024)) != 1024:
                raise ValueError("Stream probe failed")
    temporary = args.database.with_suffix(".nivuk.tmp.db")
    shutil.copyfile(args.database, temporary)
    with closing(sqlite3.connect(temporary)) as db:
        db.execute("DELETE FROM audio_chapters WHERE version_code='NIVUK'")
        db.executemany("INSERT INTO audio_chapters VALUES ('NIVUK',?,?,?,?,?)", [
            (item["order"], item["book"], item["chapter"],
             f"NIVUK_{item['order']:04d}_{item['book']}_{item['chapter']:03d}.mp3", item["url"])
            for item in results])
        db.execute("INSERT OR REPLACE INTO metadata VALUES ('nivuk_audio_bytes',?)", (str(sum(item["bytes"] for item in results)),))
        db.execute("INSERT OR REPLACE INTO metadata VALUES ('nivuk_audio_source','BibleGateway: NIVUK by David Suchet; Biblica 2013')")
        for table, expected in original.items():
            current = db.execute(f"SELECT * FROM {table} ORDER BY rowid").fetchall()
            if table == "audio_chapters":
                expected = [row for row in expected if row[0] != "NIVUK"]
                current = [row for row in current if row[0] != "NIVUK"]
            if current != expected:
                raise ValueError(f"Existing content changed: {table}")
        db.execute("PRAGMA user_version=5")
        db.commit()
        if db.execute("PRAGMA integrity_check").fetchone() != ("ok",):
            raise ValueError("SQLite integrity check failed")
    temporary.replace(args.database)
    print(f"Imported 1189 Suchet NIVUK links; {sum(item['bytes'] for item in results)} bytes on demand. Existing content preserved.", flush=True)


if __name__ == "__main__":
    main()
