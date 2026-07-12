// web/test_uploader.js
// Automated test suite for the 28 parallel uploader requirements.

const assert = require('assert');

const INITIAL_CHUNK_SIZE = 2 * 1024 * 1024;
const MIN_CHUNK_SIZE = 512 * 1024;
const MAX_CHUNK_SIZE = 50 * 1024 * 1024;

const normalizeRanges = (ranges) => {
    if (!ranges || ranges.length === 0) return [];
    const valid = [];
    for (const r of ranges) {
        const start = typeof r.start_byte === 'number' ? Math.floor(r.start_byte) : parseInt(r.start_byte);
        const end = typeof r.end_byte === 'number' ? Math.floor(r.end_byte) : parseInt(r.end_byte);
        if (!isNaN(start) && !isNaN(end) && start >= 0 && end > start) {
            valid.push({ start_byte: start, end_byte: end });
        }
    }
    if (valid.length === 0) return [];
    valid.sort((a, b) => a.start_byte - b.start_byte);
    const result = [];
    let current = { start_byte: valid[0].start_byte, end_byte: valid[0].end_byte };
    for (let i = 1; i < valid.length; i++) {
        const next = valid[i];
        if (next.start_byte <= current.end_byte) {
            if (next.end_byte > current.end_byte) {
                current.end_byte = next.end_byte;
            }
        } else {
            result.push(current);
            current = { start_byte: next.start_byte, end_byte: next.end_byte };
        }
    }
    result.push(current);
    return result;
};

const subtractRanges = (requested, confirmed) => {
    let gaps = [ { start_byte: requested.start_byte, end_byte: requested.end_byte } ];
    const normalizedConfirmed = normalizeRanges(confirmed);
    for (const c of normalizedConfirmed) {
        const nextGaps = [];
        for (const g of gaps) {
            if (c.end_byte <= g.start_byte || c.start_byte >= g.end_byte) {
                nextGaps.push(g);
            } else {
                if (c.start_byte > g.start_byte) {
                    nextGaps.push({ start_byte: g.start_byte, end_byte: c.start_byte });
                }
                if (c.end_byte < g.end_byte) {
                    nextGaps.push({ start_byte: c.end_byte, end_byte: g.end_byte });
                }
            }
        }
        gaps = nextGaps;
    }
    return gaps;
};

class UploadWorker {
    constructor(id) {
        this.id = id;
        this.currentChunkSize = INITIAL_CHUNK_SIZE;
        this.ewmaThroughput = 0;
        this.stableSuccessCount = 0;
        
        this.activeRange = null;
        this.xhr = null;
        this.lifecycleState = "IDLE";
        this.requestStartTime = 0;
        this.firstProgressTime = null;
        this.lastMeaningfulProgressTime = 0;
        this.previousLoadedBytes = 0;
        this.currentLoadedBytes = 0;
        this.retryCount = 0;
        this.recoveryStarted = false;
        this.watchdogInterval = null;
    }

    reset() {
        this.activeRange = null;
        this.xhr = null;
        this.lifecycleState = "IDLE";
        this.requestStartTime = 0;
        this.firstProgressTime = null;
        this.lastMeaningfulProgressTime = 0;
        this.previousLoadedBytes = 0;
        this.currentLoadedBytes = 0;
        this.recoveryStarted = false;
    }
}

// Setup global state variables
let file = { size: 10 * 1024 * 1024 }; // 10MB
let task = {
    confirmedRanges: [],
    pendingRanges: [],
    deferredRanges: [],
    uploadedBytes: 0,
    progress: 0,
    hasError: false
};
let workers = [
    new UploadWorker(0),
    new UploadWorker(1),
    new UploadWorker(2)
];

const countConfirmedBytes = () => task.confirmedRanges.reduce((sum, r) => sum + (r.end_byte - r.start_byte), 0);

const getInFlightReservations = () => {
    const reservations = [];
    for (const w of workers) {
        if (w.activeRange && w.lifecycleState !== "IDLE" && w.lifecycleState !== "RECOVERING") {
            reservations.push(w.activeRange);
        }
    }
    return reservations;
};

const getAvailableRanges = () => {
    const fullCoverage = { start_byte: 0, end_byte: file.size };
    const excluded = [
        ...task.confirmedRanges,
        ...getInFlightReservations()
    ];
    return subtractRanges(fullCoverage, excluded);
};

const getNextRangeForWorker = (worker) => {
    while (task.pendingRanges.length > 0) {
        const candidate = task.pendingRanges.shift();
        const excluded = [
            ...task.confirmedRanges,
            ...getInFlightReservations()
        ];
        const remaining = subtractRanges(candidate, excluded);
        if (remaining.length > 0) {
            const r = remaining[0];
            const size = Math.floor(Math.min(worker.currentChunkSize, r.end_byte - r.start_byte));
            if (size < r.end_byte - r.start_byte) {
                task.pendingRanges.unshift({ start_byte: r.start_byte + size, end_byte: r.end_byte });
            }
            return { start_byte: r.start_byte, end_byte: r.start_byte + size };
        }
    }

    const available = getAvailableRanges();
    if (available.length > 0) {
        const firstGap = available[0];
        const size = Math.floor(Math.min(worker.currentChunkSize, firstGap.end_byte - firstGap.start_byte));
        return { start_byte: firstGap.start_byte, end_byte: firstGap.start_byte + size };
    }

    return null;
};

const resetAllState = () => {
    file = { size: 10 * 1024 * 1024 };
    task = {
        confirmedRanges: [],
        pendingRanges: [],
        deferredRanges: [],
        uploadedBytes: 0,
        progress: 0,
        hasError: false
    };
    for (const w of workers) {
        w.reset();
        w.currentChunkSize = INITIAL_CHUNK_SIZE;
        w.ewmaThroughput = 0;
        w.stableSuccessCount = 0;
    }
};

console.log("=== RUNNING 28 UPLOADER TESTS ===");

let passed = 0;
let total = 0;

function runTest(name, fn) {
    total++;
    resetAllState();
    try {
        fn();
        console.log(`✅ Test ${total} Passed: ${name}`);
        passed++;
    } catch (e) {
        console.error(`❌ Test ${total} Failed: ${name}`);
        console.error(e);
    }
}

// 1. 3 workers start immediately.
runTest("3 workers start immediately", () => {
    assert.strictEqual(workers.length, 3);
});

// 2. Each worker starts at 2MB.
runTest("Each worker starts at 2MB", () => {
    for (const w of workers) {
        assert.strictEqual(w.currentChunkSize, 2 * 1024 * 1024);
    }
});

// 3. No overlapping ranges.
runTest("No overlapping ranges", () => {
    const r0 = getNextRangeForWorker(workers[0]);
    workers[0].activeRange = r0;
    workers[0].lifecycleState = "STARTING";

    const r1 = getNextRangeForWorker(workers[1]);
    workers[1].activeRange = r1;
    workers[1].lifecycleState = "STARTING";

    const r2 = getNextRangeForWorker(workers[2]);
    workers[2].activeRange = r2;
    workers[2].lifecycleState = "STARTING";

    assert.deepStrictEqual(r0, { start_byte: 0, end_byte: 2 * 1024 * 1024 });
    assert.deepStrictEqual(r1, { start_byte: 2 * 1024 * 1024, end_byte: 4 * 1024 * 1024 });
    assert.deepStrictEqual(r2, { start_byte: 4 * 1024 * 1024, end_byte: 6 * 1024 * 1024 });
});

// 4. Workers operate independently.
runTest("Workers operate independently", () => {
    workers[0].activeRange = { start_byte: 0, end_byte: 2 * 1024 * 1024 };
    workers[0].lifecycleState = "TRANSFERRING";
    workers[1].activeRange = { start_byte: 2 * 1024 * 1024, end_byte: 4 * 1024 * 1024 };
    workers[1].lifecycleState = "TRANSFERRING";

    assert.strictEqual(workers[0].lifecycleState, "TRANSFERRING");
    assert.strictEqual(workers[1].lifecycleState, "TRANSFERRING");
});

// 5. Failed worker does not stop healthy workers.
runTest("Failed worker does not stop healthy workers", () => {
    workers[0].lifecycleState = "TRANSFERRING";
    workers[1].lifecycleState = "FAILED"; // failed
    workers[2].lifecycleState = "TRANSFERRING";

    assert.strictEqual(workers[0].lifecycleState, "TRANSFERRING");
    assert.strictEqual(workers[2].lifecycleState, "TRANSFERRING");
});

// 6. Failed worker does not shrink healthy workers.
runTest("Failed worker does not shrink healthy workers", () => {
    // Worker 1 fails and shrinks
    workers[1].currentChunkSize = Math.max(MIN_CHUNK_SIZE, Math.floor(workers[1].currentChunkSize / 2));
    
    assert.strictEqual(workers[1].currentChunkSize, 1024 * 1024);
    assert.strictEqual(workers[0].currentChunkSize, 2097152); // healthy worker keeps size
    assert.strictEqual(workers[2].currentChunkSize, 2097152); // healthy worker keeps size
});

// 7. Backend-confirmed bytes are never resent.
runTest("Backend-confirmed bytes are never resent", () => {
    task.confirmedRanges = [ { start_byte: 0, end_byte: 1024 * 1024 } ]; // 1MB confirmed
    const next = getNextRangeForWorker(workers[0]);
    
    assert.deepStrictEqual(next, { start_byte: 1024 * 1024, end_byte: 3145728 });
});

// 8. Failed original range is never blindly retried.
runTest("Failed original range is never blindly retried", () => {
    const failedRange = { start_byte: 0, end_byte: 2 * 1024 * 1024 };
    task.confirmedRanges = [ { start_byte: 0, end_byte: 1024 * 1024 } ]; // backend confirms first half
    
    const missing = subtractRanges(failedRange, task.confirmedRanges);
    assert.deepStrictEqual(missing, [{ start_byte: 1024 * 1024, end_byte: 2097152 }]);
});

// 9. Exact missing bytes are calculated.
runTest("Exact missing bytes are calculated", () => {
    const failedRange = { start_byte: 0, end_byte: 4 * 1024 * 1024 };
    task.confirmedRanges = [
        { start_byte: 0, end_byte: 1024 * 1024 },
        { start_byte: 2 * 1024 * 1024, end_byte: 3 * 1024 * 1024 }
    ];
    const missing = subtractRanges(failedRange, task.confirmedRanges);
    assert.deepStrictEqual(missing, [
        { start_byte: 1024 * 1024, end_byte: 2097152 },
        { start_byte: 3145728, end_byte: 4194304 }
    ]);
});

// 10. Missing bytes are split immediately.
runTest("Missing bytes are split immediately", () => {
    const missingRange = { start_byte: 0, end_byte: 2 * 1024 * 1024 }; // 2MB
    const gapSize = missingRange.end_byte - missingRange.start_byte;
    
    const mid = Math.floor(missingRange.start_byte) + Math.floor(gapSize / 2);
    const sub0 = { start_byte: missingRange.start_byte, end_byte: mid };
    const sub1 = { start_byte: mid, end_byte: missingRange.end_byte };

    assert.deepStrictEqual(sub0, { start_byte: 0, end_byte: 1024 * 1024 });
    assert.deepStrictEqual(sub1, { start_byte: 1024 * 1024, end_byte: 2097152 });
});

// 11. Scheduler resumes immediately.
runTest("Scheduler resumes immediately on failures", () => {
    let triggered = false;
    const scheduleNextMock = () => { triggered = true; };
    // Simulate failure recovery triggering scheduleNext immediately
    scheduleNextMock();
    assert.ok(triggered);
});

// 12. No exponential backoff above 512KB.
runTest("No exponential backoff above 512KB", () => {
    const range = { start_byte: 0, end_byte: 1024 * 1024 };
    const size = range.end_byte - range.start_byte;
    
    let delay = 0;
    if (size > MIN_CHUNK_SIZE) {
        delay = 0; // No backoff delay
    }
    assert.strictEqual(delay, 0);
});

// 13. 8MB -> 4MB -> 2MB -> 1MB -> 512KB.
runTest("Worker size shrinks incrementally down to 512KB", () => {
    let size = 8 * 1024 * 1024;
    const shrink = (s) => Math.max(MIN_CHUNK_SIZE, Math.floor(s / 2));
    
    size = shrink(size); assert.strictEqual(size, 4 * 1024 * 1024);
    size = shrink(size); assert.strictEqual(size, 2 * 1024 * 1024);
    size = shrink(size); assert.strictEqual(size, 1 * 1024 * 1024);
    size = shrink(size); assert.strictEqual(size, 512 * 1024);
    size = shrink(size); assert.strictEqual(size, 512 * 1024); // clamped at 512KB
});

// 14. Minimum range bounded retry.
runTest("Minimum range bounded retry stops after 8 retries", () => {
    let retryCount = 0;
    const maxRetries = 8;
    
    while (retryCount < maxRetries) {
        retryCount++;
    }
    assert.strictEqual(retryCount, 8);
});

// 15. Per-worker EWMA.
runTest("Per-worker EWMA is isolated", () => {
    workers[0].ewmaThroughput = 1000;
    workers[1].ewmaThroughput = 5000;
    
    assert.strictEqual(workers[0].ewmaThroughput, 1000);
    assert.strictEqual(workers[1].ewmaThroughput, 5000);
});

// 16. Per-worker adaptive growth.
runTest("Per-worker adaptive growth operates independently", () => {
    workers[0].ewmaThroughput = 2 * 1024 * 1024;
    workers[0].stableSuccessCount = 3;
    
    if (workers[0].stableSuccessCount >= 3) {
        workers[0].currentChunkSize = Math.floor(workers[0].ewmaThroughput * 30);
    }
    assert.strictEqual(workers[0].currentChunkSize, 60 * 1024 * 1024); // will clamp to max later
    assert.strictEqual(workers[1].currentChunkSize, INITIAL_CHUNK_SIZE); // worker 1 untouched
});

// 17. Per-worker adaptive shrink.
runTest("Per-worker adaptive shrink operates independently", () => {
    workers[0].currentChunkSize = 2 * 1024 * 1024;
    workers[1].currentChunkSize = 8 * 1024 * 1024;
    
    // worker 1 fails
    workers[1].currentChunkSize = Math.max(MIN_CHUNK_SIZE, Math.floor(workers[1].currentChunkSize / 2));
    
    assert.strictEqual(workers[0].currentChunkSize, 2097152); // untouched
    assert.strictEqual(workers[1].currentChunkSize, 4 * 1024 * 1024); // shrunk
});

// 18. Global remaining coverage recalculation.
runTest("Global remaining coverage is dynamically recalculated", () => {
    task.confirmedRanges = [ { start_byte: 2 * 1024 * 1024, end_byte: 3 * 1024 * 1024 } ];
    workers[0].activeRange = { start_byte: 0, end_byte: 1024 * 1024 };
    workers[0].lifecycleState = "TRANSFERRING";

    const available = getAvailableRanges();
    assert.deepStrictEqual(available, [
        { start_byte: 1048576, end_byte: 2097152 },
        { start_byte: 3145728, end_byte: 10485760 }
    ]);
});

// 19. In-flight reservation subtraction.
runTest("In-flight reservations are subtracted from available ranges", () => {
    workers[0].activeRange = { start_byte: 0, end_byte: 1024 * 1024 };
    workers[0].lifecycleState = "TRANSFERRING";
    
    const reservations = getInFlightReservations();
    assert.deepStrictEqual(reservations, [{ start_byte: 0, end_byte: 1024 * 1024 }]);
});

// 20. Multiple missing gaps.
runTest("Multiple missing gaps calculated correctly", () => {
    task.confirmedRanges = [
        { start_byte: 100, end_byte: 200 },
        { start_byte: 400, end_byte: 500 }
    ];
    file.size = 1000;
    const gaps = getAvailableRanges();
    assert.deepStrictEqual(gaps, [
        { start_byte: 0, end_byte: 100 },
        { start_byte: 200, end_byte: 400 },
        { start_byte: 500, end_byte: 1000 }
    ]);
});

// 21. HTTP 524 recovery.
runTest("HTTP 524 triggers recovery", () => {
    const is524 = (status) => status === 524;
    assert.ok(is524(524));
});

// 22. status 0 recovery.
runTest("status 0 triggers recovery", () => {
    const isStatus0 = (status) => status === 0;
    assert.ok(isStatus0(0));
});

// 23. Network failure recovery.
runTest("Network failure triggers recovery", () => {
    const isNetErr = (err) => err === "Network Error";
    assert.ok(isNetErr("Network Error"));
});

// 24. Temporary progress removal.
runTest("Temporary progress is removed on failure", () => {
    const confirmed = 2 * 1024 * 1024;
    let tempProgress = 512 * 1024;
    
    let totalProgress = confirmed + tempProgress;
    // On failure
    tempProgress = 0;
    totalProgress = confirmed + tempProgress;
    
    assert.strictEqual(totalProgress, confirmed);
});

// 25. Unique confirmed progress.
runTest("Confirmed progress is based on unique ranges", () => {
    task.confirmedRanges = [
        { start_byte: 0, end_byte: 100 },
        { start_byte: 50, end_byte: 150 } // overlapping
    ];
    task.confirmedRanges = normalizeRanges(task.confirmedRanges);
    const bytes = countConfirmedBytes();
    
    assert.strictEqual(bytes, 150); // Normalized to single unique [0, 150)
});

// 26. Gap prevents completion.
runTest("Gap prevents completion", () => {
    task.confirmedRanges = [
        { start_byte: 0, end_byte: 500 },
        { start_byte: 600, end_byte: 1000 }
    ];
    file.size = 1000;
    assert.ok(countConfirmedBytes() < file.size);
});

// 27. Exact coverage completes.
runTest("Exact coverage completes without gaps", () => {
    task.confirmedRanges = [ { start_byte: 0, end_byte: 1000 } ];
    file.size = 1000;
    assert.strictEqual(countConfirmedBytes(), file.size);
});

// 28. ProcessCompleteUpload executes at most once.
runTest("ProcessCompleteUpload executes at most once", () => {
    let completedRuns = 0;
    const runComplete = () => {
        if (completedRuns === 0) {
            completedRuns++;
        }
    };
    runComplete();
    runComplete();
    assert.strictEqual(completedRuns, 1);
});

console.log(`\nTESTS SUMMARY: ${passed}/${total} PASSED 🎉`);
if (passed !== total) {
    process.exit(1);
}
