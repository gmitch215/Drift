def label(status):
    match status:
        case "ok":
            return "passed"
        case "fail":
            return "failed"
        case _:
            return "unknown"


def summarize(results):
    counts = {"passed": 0, "failed": 0, "unknown": 0}
    for status in results:
        counts[label(status)] += 1
    return counts


def test_label():
    assert label("ok") == "passed"
    assert label("fail") == "failed"
    assert label("skip") == "unknown"


def test_summarize():
    assert summarize(["ok", "ok", "fail"]) == {"passed": 2, "failed": 1, "unknown": 0}


if __name__ == "__main__":
    test_label()
    test_summarize()
    print("test_report: ok")
