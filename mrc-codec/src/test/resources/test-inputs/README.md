# Test Input Files

Test input files for evaluating MRC compression performance on various data patterns.

## Files

### random-500kb.bin (500 KB)
- **Pattern:** Completely random bytes
- **Seed:** 42
- **Use case:** Test compression on incompressible data
- **Expected compression:** ~1.0x (no compression)

### arithmetic-400kb.bin (400 KB)
- **Pattern:** Arithmetic sequence with delta=3 (repeating 10→13→16→19→...)
- **Use case:** Test compression on highly compressible patterns
- **Expected compression:** < 0.3x (high compression gain from cycles)

### text-like-300kb.bin (300 KB)
- **Pattern:** ASCII-like characters (bytes 32-95, simulating text)
- **Seed:** 123
- **Use case:** Test compression on natural text-like data
- **Expected compression:** ~0.5-0.8x (moderate compression)

### repetitive-100kb.bin (100 KB)
- **Pattern:** Single repeated byte (value 42, 0x2A)
- **Use case:** Test compression on maximally compressible data
- **Expected compression:** < 0.1x (extreme compression from LITERAL tier)

## Total Size

All files combined: **1.3 MB**

## Usage in Tests

Load files in test code:
```java
byte[] data = Files.readAllBytes(
    Paths.get("src/test/resources/test-inputs/random-500kb.bin")
);
```

## Generating New Test Inputs

Run the GenerateTestInputs program:
```bash
javac GenerateTestInputs.java
java GenerateTestInputs
```

Modify file sizes or patterns in the source code as needed.
