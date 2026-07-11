// web/test_scheduler.js
// Isolated test suite for the Adaptive Byte-Range Uploader algorithms.

const assert = require('assert');

// 1. Range Normalization Algorithm
function normalizeRanges(ranges) {
    if (ranges.length <= 1) return ranges;
    const sorted = [...ranges].sort((a, b) => a.start_byte - b.start_byte);
    const merged = [];
    let current = sorted[0];
    for (let i = 1; i < sorted.length; i++) {
        const next = sorted[i];
        if (next.start_byte <= current.end_byte) {
            if (next.end_byte > current.end_byte) {
                current.end_byte = next.end_byte;
            }
        } else {
            merged.push(current);
            current = next;
        }
    }
    merged.push(current);
    return merged;
}

// 2. Range Subtraction Algorithm
function subtractRanges(totalSize, confirmedRanges) {
    const normalized = normalizeRanges(confirmedRanges);
    const missing = [];
    let currentPos = 0;
    for (const r of normalized) {
        if (r.start_byte > currentPos) {
            missing.push({ start_byte: currentPos, end_byte: r.start_byte });
        }
        if (r.end_byte > currentPos) {
            currentPos = r.end_byte;
        }
    }
    if (currentPos < totalSize) {
        missing.push({ start_byte: currentPos, end_byte: totalSize });
    }
    return missing;
}

// 3. EWMA Speed Calculation
function updateEwma(currentEwma, newSample) {
    if (currentEwma === 0) return newSample;
    return (currentEwma * 0.8) + (newSample * 0.2);
}

// 4. Adaptive Chunk Sizing
function getNextChunkSize(ewmaSpeed) {
    let nextChunkSize = 5 * 1024 * 1024; // default 5MB
    if (ewmaSpeed > 0) {
        nextChunkSize = Math.floor(ewmaSpeed * 20); // 20-second target
    }
    // Clamp to [512KB, 50MB]
    return Math.max(512 * 1024, Math.min(50 * 1024 * 1024, nextChunkSize));
}

// === RUN TESTS ===

console.log("Running Isolated Scheduler Tests...");

// Test 1: Normalize Ranges
const testRanges1 = [
    { start_byte: 10, end_byte: 20 },
    { start_byte: 0, end_byte: 5 },
    { start_byte: 5, end_byte: 12 },
    { start_byte: 30, end_byte: 40 }
];
const normalized1 = normalizeRanges(testRanges1);
console.log("Normalized 1:", normalized1);
assert.deepStrictEqual(normalized1, [
    { start_byte: 0, end_byte: 20 },
    { start_byte: 30, end_byte: 40 }
]);
console.log("✅ Test 1 Passed: normalizeRanges");

// Test 2: Subtract Ranges
const totalSize2 = 100;
const testRanges2 = [
    { start_byte: 10, end_byte: 20 },
    { start_byte: 40, end_byte: 60 }
];
const subtracted2 = subtractRanges(totalSize2, testRanges2);
console.log("Subtracted 2:", subtracted2);
assert.deepStrictEqual(subtracted2, [
    { start_byte: 0, end_byte: 10 },
    { start_byte: 20, end_byte: 40 },
    { start_byte: 60, end_byte: 100 }
]);
console.log("✅ Test 2 Passed: subtractRanges");

// Test 3: EWMA speed update
let ewma = 0;
ewma = updateEwma(ewma, 1000); // cold start
assert.strictEqual(ewma, 1000);
ewma = updateEwma(ewma, 2000);
assert.strictEqual(ewma, 1200); // 1000 * 0.8 + 2000 * 0.2 = 800 + 400 = 1200
console.log("✅ Test 3 Passed: EWMA calculations");

// Test 4: Adaptive Chunk Sizing
// Target is speed * 20
assert.strictEqual(getNextChunkSize(0), 5 * 1024 * 1024); // default 5MB
assert.strictEqual(getNextChunkSize(100), 512 * 1024); // speed 100 => 2000 => clamped to min 512KB
assert.strictEqual(getNextChunkSize(5 * 1024 * 1024), 50 * 1024 * 1024); // speed 5MB/s => 100MB => clamped to max 50MB
assert.strictEqual(getNextChunkSize(100 * 1024), 2000 * 1024); // speed 100KB/s => 2MB (within limits)
console.log("✅ Test 4 Passed: Adaptive Chunk Sizing");

console.log("\nALL TESTS PASSED SUCCESSFULLY! 🎉");
