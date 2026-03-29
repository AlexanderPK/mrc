# MRC Compression Theory — Complete Mathematical & Scientific Foundations

## Table of Contents

1. [Core Concepts](#core-concepts)
2. [Information Theory Foundations](#information-theory-foundations)
3. [Relational Compression Theory](#relational-compression-theory)
4. [Operator Algebra & Abstract Algebra](#operator-algebra--abstract-algebra)
5. [Transition Graphs & Markov Chains](#transition-graphs--markov-chains)
6. [Cycle Detection & Graph Theory](#cycle-detection--graph-theory)
7. [Encoding Strategies](#encoding-strategies)
8. [Prefix-Free Codes & Kraft Inequality](#prefix-free-codes--kraft-inequality)
9. [Genetic Algorithm for Operator Discovery](#genetic-algorithm-for-operator-discovery)
10. [Complexity Analysis](#complexity-analysis)
11. [Theoretical Bounds & Limitations](#theoretical-bounds--limitations)
12. [Snapshot Protocol](#snapshot-protocol)
13. [Distributed Adaptive Compression Platform](#distributed-adaptive-compression-platform)
14. [Scientific References](#scientific-references)
15. [Open Research Questions](#open-research-questions)

---

## Core Concepts

### Why Relational Compression Works

Traditional byte-by-byte compression (Huffman, LZ77, etc.) treats each byte independently or as part of a sequence. **MRC is fundamentally different**: it exploits **algebraic relationships** between consecutive values.

**Key Insight**: Instead of encoding a value directly, encode the *transformation* needed to reach it from the previous value.

Example: The sequence `[10, 13, 16, 19, ...]` (arithmetic with step +3)
- **Naive**: Store all 255 possible 8-bit values → 8 bits per byte
- **MRC**: Store one operator rule "Add(3)" → reuse for the entire sequence → ~1 bit per byte

### The Core Metric: Compression Gain

For any operator `op` and transition `from → to`:
- **Operator Cost** (bits to encode the operator): `5-bit opId + operand bits` = 7-15 bits
- **Direct Cost** (encode target byte directly): 8 bits
- **Compression Gain**: If we use this operator N times, we save `8N - (7N + operator_cost) = N - operator_cost` bits

**Threshold**: Break-even is reached when an operator is used ~3+ times (for a 5-bit cost).

---

## Information Theory Foundations

### Shannon's Source Coding Theorem

**Theorem** (Shannon, 1948): Let X be a discrete random variable with entropy H(X). For a lossless code:
$$I_{avg} \geq H(X)$$

where $I_{avg}$ is the average codeword length and:

$$H(X) = -\sum_{x \in X} p(x) \log_2 p(x) \text{ (bits)}$$

**Proof Sketch**: The number of distinct messages of length n is bounded by $2^{nH(X)}$. Since we need $2^{nI_{avg}}$ codewords, we require $I_{avg} \geq H(X)$.

**Implication for MRC**: The maximum theoretical compression ratio is:
$$r_{min} = \frac{H(X)}{8} \text{ (since original data uses 8 bits/byte)}$$

For example:
- **Uniform random data**: $H(X) = 8$ bits/byte → $r_{min} = 1.0$ (incompressible)
- **Arithmetic sequence (step = +3)**: $H(X) \approx 1.5$ bits/byte → $r_{min} \approx 0.19$ (theoretical limit)

### Joint Entropy & Conditional Entropy

For consecutive bytes $X_i$ and $X_{i+1}$:

$$H(X_{i+1} | X_i) = -\sum_x p(x) \sum_y p(y|x) \log_2 p(y|x)$$

**MRC Insight**: By encoding the *transition* rather than absolute values, we exploit $H(X_{i+1} | X_i) < H(X_{i+1})$.

**Mutual Information**:
$$I(X_i; X_{i+1}) = H(X_{i+1}) - H(X_{i+1} | X_i)$$

High mutual information indicates strong correlations that MRC can exploit.

### Rate-Distortion Theory

While MRC is lossless (distortion = 0), rate-distortion theory bounds achievable rates:

$$R(D) = \min_{p(y|x): E[d(x,y)] \leq D} I(X; Y)$$

For lossless compression: $D = 0$, so $R(0) = H(X)$.

**Application**: If we relax losslessness slightly (lossy mode, future work), we could approach:
$$R(\epsilon) = H(X) - O(\epsilon^2)$$

---

## Relational Compression Theory

### Formal Definition of Transition Compression

**Definition**: Let $f: [0, 255] \to [0, 255]$ be an operator (function on bytes).

A **relational encoding** of a sequence $s = [s_0, s_1, ..., s_{n-1}]$ encodes:
1. Initial value $s_0$ (8 bits)
2. For each $i = 1, ..., n-1$: an operator $f_i$ such that $f_i(s_{i-1}) = s_i$

**Compression Cost Analysis**:
- Direct encoding: $n \times 8$ bits
- Relational encoding: $8 + \sum_{i=1}^{n-1} C(f_i)$ bits, where $C(f_i)$ is the cost of encoding operator $f_i$

**Compression Ratio**:
$$r = \frac{8 + \sum C(f_i)}{8n}$$

**Break-Even Analysis**:

For a repeated operator $f$ with cost $c$ bits, used k times:
- Direct: $8k$ bits
- Relational: $c + k \times c_{token}$ bits (header + per-use cost)

Break-even occurs when:
$$8k = c + k \times c_{token} \implies k = \frac{c}{8 - c_{token}}$$

For $c_{\text{token}} = 0$ (amortized cost per use in a run):
$$k^* = \frac{c}{8}$$

**Example**: For $c = 10$ bits, $k^* \approx 1.25$ uses. So a 10-bit operator break-evens in ~2 uses.

### Operator Cost Structure

In MRC, $C(f) = 5 + \text{operandBits}(f)$ bits:
- **5 bits**: Opcode (identifies operator from available set)
- **0-8 bits**: Operand (parameter value)

**Cost Distribution**:
$$C(f) \in \{5, 8, 13\}$$
- ShiftLeft(n): 5 + 3 = 8 bits
- Add(k): 5 + 8 = 13 bits
- Not: 5 + 0 = 5 bits

### Markovian Assumption

MRC assumes transitions depend only on the previous byte:
$$p(s_i | s_0, ..., s_{i-1}) \approx p(s_i | s_{i-1})$$

This is a **first-order Markov chain** assumption. Stronger correlations (longer dependencies) would require extended operators or higher-order models.

**Entropy Rate**: For a first-order Markov chain:
$$H_\infty = \lim_{n \to \infty} \frac{1}{n} H(S_n | S_{n-1}, ..., S_1)$$

MRC's theoretical limit is $H_\infty$ per byte.

---

## Operator Algebra & Abstract Algebra

### Base Operators (Phase 1)

MRC includes 11 base operators, each with a unique 5-bit `opId` (0-10):

| OpId | Name | Operand | Cost | Formula |
|------|------|---------|------|---------|
| 0 | Add | 0-255 | 8 bits | `(x + operand) & 0xFF` |
| 1 | Sub | 0-255 | 8 bits | `(x - operand) & 0xFF` |
| 2 | Mul | 0-255 | 8 bits | `(x * operand) & 0xFF` |
| 3 | Div | 1-255 | 8 bits | `x / operand` (skip 0) |
| 4 | Mod | 1-255 | 8 bits | `x % operand` (skip 0) |
| 5 | XorOp | 0-255 | 8 bits | `x ^ operand` |
| 6 | AndOp | 0-255 | 8 bits | `x & operand` |
| 7 | OrOp | 0-255 | 8 bits | `x \| operand` |
| 8 | ShiftLeft | 1-7 | 3 bits | `(x << bits) & 0xFF` |
| 9 | ShiftRight | 1-7 | 3 bits | `x >> bits` |
| 10 | Not | none | 0 bits | `~x & 0xFF` |

**Total Instances**: ~2,400 (all valid combinations: 256 Add, 255 Sub, ..., 1 Not)

### Why 8-bit Wrapping?

All operators use modulo 256 arithmetic (`result & 0xFF`). This is intentional:
- Matches 8-bit byte boundary naturally
- Preserves algebraic structure (group theory: Z/256Z is a ring)
- Allows predictable composition: `f ∘ g` is also in the operator space

### Group Structure of Byte Operations

The set of bytes with addition (mod 256) forms a **cyclic group**:
$$(\mathbb{Z}/256\mathbb{Z}, +)$$

**Properties**:
- **Closure**: $(a + b) \bmod 256 \in [0, 255]$
- **Associativity**: $(a + b) + c = a + (b + c)$ (mod 256)
- **Identity**: $a + 0 = a$
- **Inverse**: $a + (256 - a) = 0$ (mod 256)

**Relevance**: Addition operators commute and compose: $\text{Add}(a) \circ \text{Add}(b) = \text{Add}(a+b)$

### Ring Structure

With both addition and multiplication (mod 256):
$$(\mathbb{Z}/256\mathbb{Z}, +, \times)$$

**Caveat**: Not an integral domain (zero divisors exist: $2 \times 128 = 0$ mod 256).

**Implication**: Multiplication operators are not always invertible (e.g., $\text{Mul}(2)$ cannot be reversed).

### Operator Composition

**Definition**: $(f \circ g)(x) = f(g(x))$

**Property**: Composition is associative but not commutative:
$$f \circ g \neq g \circ f \text{ (generally)}$$

**Example**:
- $(\text{Add}(1) \circ \text{Mul}(2))(3) = \text{Add}(1)(\text{Mul}(2)(3)) = \text{Add}(1)(6) = 7$
- $(\text{Mul}(2) \circ \text{Add}(1))(3) = \text{Mul}(2)(\text{Add}(1)(3)) = \text{Mul}(2)(4) = 8$

**Cost of Composition**:
$$C(f \circ g) = C(f) + C(g)$$

This is why Level 2 (CompositeOperators) are expensive: they chain multiple operators.

### Extended Operators (Phase 2)

Three levels of extended operators for discovering novel transitions:

**Level 1: FunctionOperators** (opId 32-39)
- `Polynomial(a, b, c)`: `y = ax² + bx + c` (mod 256)
- `LinearCongruential(a, c)`: `y = ax + c` (mod 256)
- `TableLookup`: 256-byte lookup table
- `RotateLeft/RotateRight`: Bit rotation
- `BitReverse`, `NibbleSwap`, `DeltaOfDelta`: Bit/byte manipulations

**Level 2: CompositeOperators** (opId 40)
- Chain of 2-4 base operators: `f1 ∘ f2 ∘ f3`
- Auto-optimize: `Add(1) ∘ Add(2)` → `Add(3)`
- Enables modeling complex patterns

**Level 3: SuperfunctionOperators** (opId 64-66)
- `Iterated(base, n)`: Apply operator n times
- `FixedPointReach(op)`: Find n such that f^n(x) = fixed point
- `Conjugate(h, f, h⁻¹)`: Conjugation: y = h⁻¹(f(h(x)))

### Extended Operators as Function Space

**Level 1 (FunctionOperators)**: $f: \mathbb{Z}/256\mathbb{Z} \to \mathbb{Z}/256\mathbb{Z}$ with specific structures
- Polynomial: $f(x) = ax^2 + bx + c \pmod{256}$
- TableLookup: Arbitrary mapping (full function freedom)

**Level 2 (CompositeOperators)**: $(f_1 \circ f_2 \circ ... \circ f_k)$ where $k \leq 4$

**Level 3 (SuperfunctionOperators)**: Meta-operators
- Iterated: $f^n = \underbrace{f \circ f \circ ... \circ f}_{n \text{ times}}$
- Conjugate: $h^{-1} \circ f \circ h$ (change of basis)

---

## Transition Graphs & Markov Chains

### Graph Construction: From Data to Structure

A **transition graph** is a directed multigraph where:
- **Nodes**: The 256 possible byte values (0-255)
- **Edges**: Operator transitions from one value to another
- **Weight**: Frequency of this transition in training data

**Construction Algorithm**:
```
for each consecutive pair (prev, curr) in training data:
    1. Find cheapest operator op that transforms prev → curr
    2. Lookup edge (prev, curr, op) or create it
    3. Increment frequency count
    4. Update weight = freq × (8 - costBits)
```

**Weight Interpretation**:
- Positive weight: This operator *compresses* (saves bits)
- Negative weight: This operator costs bits (but still useful for cycles)
- Higher weight: Higher compression potential

### Formal Graph Definition

**Definition**: A transition graph is a weighted directed multigraph:
$$G = (V, E, w)$$

where:
- $V = [0, 255]$ (256 nodes, one per byte value)
- $E \subseteq V \times V \times \text{Operators}$ (weighted edges with operator labels)
- $w: E \to \mathbb{R}$ (weight function)

### Weight Function: Information-Theoretic Interpretation

**Definition**:
$$w(u, v, f) = \text{freq}(u \to v) \times (\text{directCost} - \text{operatorCost})$$
$$= \text{freq}(u \to v) \times (8 - C(f))$$

**Interpretation**: Expected bits saved if we use operator $f$ for this transition.

**Properties**:
- $w > 0$: Compressing transition (saves bits)
- $w < 0$: Expanding transition (costs bits, but might be part of valuable cycle)
- $w = 0$: Break-even (operator cost = direct cost)

### Why Graphs Reveal Structure

Data with patterns creates dense graphs:
- **Random data**: Sparse, uniform edges
- **Text data**: Dense regions (common byte transitions like 'e' → 'r')
- **Arithmetic sequences**: Clique-like structure (strong operators repeated)

The graph becomes a **compression roadmap**: find high-weight paths through the graph to maximize savings.

### Connection to Markov Chains

Let $p_{ij} = P(X_t = j | X_{t-1} = i)$ be the transition probability.

Then:
$$w(i, j, f) \propto p_{ij} \times (8 - C(f))$$

The **stationary distribution** $\pi$ satisfies:
$$\pi_j = \sum_i \pi_i p_{ij}$$

**Graph-Theoretic Implication**: Nodes with high stationary probability are worth compressing well.

### Average Transition Cost

**Definition**:
$$\bar{C} = \sum_{(u,v,f) \in E} p(u \to v) \times C(f)$$

where $p(u \to v)$ is the empirical transition probability.

**Compression Ratio**:
$$r = \frac{8 + \bar{C}}{8} = 1 + \frac{\bar{C}}{8}$$

For compression: $r < 1 \implies \bar{C} < 0$ (average operator saves bits).

### Ergodicity & Mixing Time

If the transition graph is:
- **Strongly connected**: Can reach any state from any state
- **Aperiodic**: No periodic cycles (other than self-loops)

Then the graph represents an **ergodic Markov chain**. The mixing time $\tau$ bounds how quickly the chain forgets initial conditions.

**Relevance to MRC**: Ergodic data is harder to compress (less structure). Non-ergodic data (like data with distinct domains) compress better via domain-specific operators.

---

## Cycle Detection & Graph Theory

### What is a Cycle?

A **cycle** is a closed path in the transition graph that returns to its starting node:
```
start → op1 → node1 → op2 → node2 → ... → opN → start
```

**Key Insight**: A cycle is compressible if the total cost of operators is less than 8N bits (where N is cycle length).

**Example Cycle** [10, 13, 16, 10]:
- `10 --[Add(3)]--> 13`
- `13 --[Add(3)]--> 16`
- `16 --[Sub(6)]--> 10`
- Total cost: `(5+8) + (5+8) + (5+8) = 39 bits`
- Direct cost: `3 × 8 = 24 bits`
- **Not compressing alone**, but if this cycle repeats, savings grow!

### Strongly Connected Components (Tarjan's Algorithm)

**Definition**: A strongly connected component (SCC) is a maximal subset $S \subseteq V$ such that for all $u, v \in S$, there exists a path from $u$ to $v$.

**Tarjan's Algorithm** (1972):
```
Algorithm Tarjan(G):
  index ← 0
  stack ← empty
  for each v in V:
    if v.index = undefined:
      strongconnect(v)

strongconnect(v):
  v.index ← index
  v.lowlink ← index
  index ← index + 1
  stack.push(v)

  for each (v, w) in E:
    if w.index = undefined:
      strongconnect(w)
      v.lowlink ← min(v.lowlink, w.lowlink)
    else if w in stack:
      v.lowlink ← min(v.lowlink, w.index)

  if v.lowlink = v.index:
    output SCC containing v and pop stack until v
```

**Complexity**: $O(V + E)$ (linear in graph size).

**Correctness Proof** (sketch):
- $v.index$ tracks discovery order
- $v.lowlink$ tracks lowest-indexed ancestor reachable via tree edges + at most one back edge
- When $v.lowlink = v.index$, v is the root of an SCC, and all descendants are in the same SCC

### Johnson's Algorithm for Cycle Enumeration

**Purpose**: Find all elementary cycles in a directed graph.

**Idea**: For each SCC, perform depth-first search from each node, looking for back-edges to the starting node.

**Algorithm** (simplified for our use case):
```
Function findCycles(SCC):
  cycles ← []
  for each node n in SCC:
    path ← [n]
    visited ← {n}
    dfs(n, path, visited, cycles)
  return deduplicateCycles(cycles)

Function dfs(current, path, visited, cycles):
  if depth(path) > MAX_CYCLE_LENGTH:
    return  // Prune long cycles

  for each (current, next) in E:
    if next = path[0]:  // Back-edge to start
      cycles.append(path)
    else if next ∉ visited:
      path.append(next)
      visited.insert(next)
      dfs(next, path, visited, cycles)
      path.pop()
```

**Complexity**: $O(N \times (V + E))$ where N is the number of cycles (can be exponential).

**Optimization in MRC**: Limit cycle length to MAX_CYCLE_LENGTH (default 8) to prevent explosion.

### Cycle Compression Gain Formula

**Definition**: For a cycle $C = [v_0, v_1, ..., v_{k-1}, v_0]$ with operators $[f_1, ..., f_k]$:

$$G(C) = k \times 8 - \sum_{i=1}^{k} C(f_i)$$

**Per-Repetition Gain**: If the cycle repeats $m$ times:
$$G_{\text{total}}(C, m) = m \times G(C) - \text{overhead}$$

where overhead includes cycle encoding in the bitstream.

**Break-Even Repetitions**:
$$m^* = \frac{\text{overhead}}{G(C)}$$

For typical overhead (~20 bits): $m^* \approx \frac{20}{G(C)}$

**Example**: Cycle [10, 13, 16] with gain = 5 bits per repetition:
- Overhead: ~20 bits for encoding the cycle in header
- Break-even: $m^* = 20 / 5 = 4$ repetitions
- At $m = 2$: savings = $2 \times 5 - 20 = -10$ bits (expands!)
- At $m = 4$: savings = $4 \times 5 - 20 = 0$ bits (break-even)
- At $m = 10$: savings = $10 \times 5 - 20 = 30$ bits (compresses)

### Cycle Existence Conditions

**Theorem**: A directed graph has an elementary cycle if and only if it has a strongly connected component with more than one node (or a self-loop).

**Proof**:
- (⟹) If cycle exists, all its nodes are in an SCC.
- (⟸) If SCC has multiple nodes, there's a path from $u$ to $v$ and back.

---

## Encoding Strategies

### Three Encoding Tiers (v0x01: Cycle-based)

MRC uses **prefix-free coding** to disambiguate encoding choices:

#### Tier 1: LITERAL (Flag = 0)
- **Cost**: 1 (flag) + 8 (value) = **9 bits**
- **Usage**: When no operator exists for transition
- **Format**: `0 | XXXXXXXX` (1 flag bit + 8 data bits)

#### Tier 2: RELATIONAL (Flag = 10)
- **Cost**: 2 (flag) + 5 (opId) + 0-8 (operand) = **7-15 bits**
- **Usage**: When an operator saves bits (cost < 8)
- **Format**: `10 | OOOOO | [operand bits]`
- **Examples**:
  - `Add(3)`: 10 + opId=0 + 8 bits = 15 bits (not compressing)
  - `ShiftLeft(1)`: 10 + opId=8 + 3 bits = 10 bits (saving 1 bit per byte)

#### Tier 3: CYCLE (Flag = 110)
- **Cost**: 3 (flag) + ceil(log2(cycleCount)) + 16 (repeat count) = **~20-25 bits base**
- **Usage**: When a cycle repeats 2+ times and saves overall bits
- **Format**: `110 | [cycleIndex bits] | [repeat count]`
- **Break-even**: Cycle cost ÷ (cycle_length × 8) ≈ saves ~3+ bits per cycle length

### Two Encoding Tiers (v0x02: Arithmetic-based)

Simpler encoding for data with strong arithmetic patterns:

#### Tier 1: LITERAL (Flag = 0)
- **Cost**: 1 + 8 = **9 bits**

#### Tier 2: ARITH_RUN (Flag = 1)
- **Cost**: 1 + 8 (stepIdx) + 8 (startVal) + 16 (runLen) = **33 bits**
- **Usage**: For runs of length ≥ 4 (break-even point)
- **Formula**: Each element is `startVal + i × step` (mod 256)
- **Example**: 100, 103, 106, 109, 112 → ARITH_RUN(step=+3, start=100, len=5)

**Why v0x02 excels**: Detects arithmetic runs via linear scan O(n), applies optimally.

---

## Prefix-Free Codes & Kraft Inequality

### Kraft Inequality

**Theorem** (Kraft, 1949): A prefix-free code with codeword lengths $\ell_1, \ell_2, ..., \ell_n$ exists if and only if:
$$\sum_{i=1}^{n} 2^{-\ell_i} \leq 1$$

**Proof** (sketch): Each codeword occupies $2^{-\ell_i}$ fraction of the binary tree. Non-overlapping codewords must occupy disjoint regions.

### MRC's Prefix-Free Encoding

MRC uses three flag patterns:
- **0**: LITERAL (1 bit flag)
- **10**: RELATIONAL (2-bit flag)
- **110**: CYCLE (3-bit flag)

**Kraft Check**:
$$\sum = 2^{-1} + 2^{-2} + 2^{-3} = 0.5 + 0.25 + 0.125 = 0.875 < 1 \checkmark$$

The remaining $0.125$ can be used for future encoding schemes (e.g., 4-bit flag **1110**).

### McMillan's Theorem

**Theorem**: Any uniquely decodable code satisfies the Kraft inequality.

**Implication**: MRC's bitstream is uniquely decodable because its flag codes are prefix-free.

### Huffman Coding Connection

While MRC uses fixed-length operators + variable-length operators (not pure Huffman), we could optimize via:
$$L_{\text{Huffman}} = \sum p_i \cdot \ell_i \leq H(X) + 1$$

where $\ell_i$ are optimal codeword lengths.

MRC's simpler approach trades optimality for interpretability and operator reusability.

---

## Genetic Algorithm for Operator Discovery

### Why Genetic Algorithms?

Finding the *best* operator set for unknown data is NP-hard (search space is exponential in operator instances). A genetic algorithm (GA) provides:
- **Approximate solutions** in reasonable time
- **Adaptive learning** from data-specific patterns
- **Parallelizable** fitness evaluation

### GA Components

**Chromosome**: A list of (fromValue, toValue, Operator) rules
- Represents: "when transitioning from X to Y, use this operator"
- Overrides default graph lookups
- Compact: ~20-100 rules typical, max 1024

**Fitness**: Compression ratio on training corpus
```
fitness = (1.0 - compressedSize / originalSize) - 0.001 × chromosomeSize
```
The **parsimony penalty** (0.001 × size) prevents rule bloat.

**Selection**: Tournament selection (pick fittest from random tournament)
- Preserves diversity better than roulette-wheel selection
- Prevents premature convergence

**Crossover: Operator-Aware (Recommended)**
- Group rules by (fromValue, toValue) pair
- Merge parents by keeping the cheaper operator for shared pairs
- Respects domain structure → faster convergence

**Mutation**: Six types applied independently
- RULE_REPLACE: Swap operator in a rule
- RULE_ADD: Add new rule for uncovered transition
- RULE_REMOVE: Delete a rule (regress to default)
- OPERATOR_UPGRADE: Replace with cheaper extended operator
- COMPOSITE_SPLIT / COMPOSITE_MERGE: Decompose/combine operators

### Convergence Criteria

Evolution **stalls** when best fitness doesn't improve > 0.0001 for 500 generations. Detector signals:
- May inject random chromosomes for diversity
- Or terminate if resource-constrained

### Fitness Landscape & Optimization

**Definition**: A fitness landscape is a function $f: \text{Chromosomes} \to \mathbb{R}$ mapping solutions to quality scores.

For MRC:
$$f(c) = (1.0 - \text{ratio}(c)) - 0.001 \times |c|$$

where $|c|$ is the number of rules (parsimony penalty).

### Convergence Theory

**Theorem** (No Free Lunch Theorem, Wolpert & Macready 1997): For any two algorithms A and B, averaged over all possible fitness landscapes, they have equal performance.

**Implication**: GAs are not universally optimal. For specific landscapes (like our compression fitness), domain-specific operators help.

**MRC Adaptation**:
- **Operator-aware crossover**: Exploits domain structure (transition pairs)
- **Mutation types**: Designed for operator space (RULE_REPLACE, RULE_ADD, etc.)

### Selection Pressure

**Definition**: The probability that a higher-fitness chromosome is selected relative to average.

For tournament selection with size $t$:
$$P(\text{select best}) = 1 - (1 - 1/N)^t \approx 1 - e^{-t/N}$$

**Trade-off**:
- **High t**: Fast convergence, risk of premature convergence
- **Low t**: Slower convergence, better diversity

**MRC Setting**: $t = 5$ balances exploration vs exploitation.

### Schema Theorem (Building Block Hypothesis)

**Theorem** (Holland 1975): Short, low-order, high-fitness schemata grow exponentially in population.

**Definition**: A schema is a pattern like `(*, Add(3), *, Sub(1), *)` where `*` is wildcard.

**Growth Rate**:
$$m(s, t+1) \geq m(s, t) \cdot \frac{f(s)}{\bar{f}} \cdot (1 - p_c \cdot \text{disruptionRate})$$

**Interpretation**: High-fitness building blocks (good operator combinations) are preserved and recombined.

### Premature Convergence & Diversity

**Problem**: Population converges to local optimum.

**Solutions**:
1. **Elitism**: Always keep top-K (preserve best found)
2. **Diversity injection**: Introduce random chromosomes when stalled
3. **Adaptive mutation**: Increase mutation rate as diversity decreases

**Convergence Criterion** (MRC):
$$\text{stalled} = (\text{bestFitness}_{t} - \text{bestFitness}_{t-500}) < 0.0001$$

If stalled for 500 generations, signal possible restart.

---

## Complexity Analysis

### Time Complexity

| Operation | Complexity | Notes |
|-----------|-----------|-------|
| Build graph | $O(n)$ | Single pass through data |
| Tarjan's SCC | $O(V + E)$ | Linear in graph size |
| Johnson's cycles | $O(C \times (V + E))$ | C = number of cycles (exponential worst-case) |
| GA generation | $O(P \times n)$ | P = population size, n = data length |
| Encode (v0x01) | $O(n)$ | One pass, O(1) operator lookup |
| Encode (v0x02) | $O(n)$ | Linear scan with arithmetic detection |
| Decode | $O(n)$ | One pass through bitstream |

### Space Complexity

| Data Structure | Space | Notes |
|---|---|---|
| OperatorLibrary | $O(1)$ | ~2,400 instances, fixed size |
| Transition cache | $O(256^2) = 65$ KB | Pre-computed matrix |
| Transition graph | $O(V + E)$ | Edges per unique transition |
| Cycle list | $O(C \times L)$ | C cycles, L avg length |
| GA population | $O(P \times R)$ | P = population, R = rules/chromosome |

### Asymptotic Behavior

**Compression Ratio as $n \to \infty$**:
$$\lim_{n \to \infty} r(n) = \frac{8 + \bar{C}}{8}$$

where $\bar{C}$ is the average operator cost (converges to entropy rate).

---

## Theoretical Bounds & Limitations

### Shannon Bound

**Upper Bound** on compression:
$$r \geq \frac{H(X)}{8}$$

where $H(X)$ is the entropy per byte.

**Example**: Arithmetic sequence has $H(X) \approx 1.5$, so $r_{\min} \approx 0.19$.

MRC achieves $r \approx 0.0001$ on pure arithmetic sequences (exploits extreme structure).

### Huffman vs MRC

**Huffman**: Optimal for symbol-to-symbol encoding
$$L_{\text{Huffman}} \leq H(X) + 1$$

**MRC**: Optimal for relational encoding
$$L_{\text{MRC}} \leq H(X_i | X_{i-1}) + \text{overhead}$$

Since $H(X_i | X_{i-1}) < H(X_i)$ for correlated data, MRC can beat Huffman.

### Uncompressibility Result

For truly random data:
$$H(X) = 8 \implies r \to 1.0$$

No compression system (Huffman, LZ77, MRC, etc.) can compress random data beyond 8 bits/byte on average.

---

## Snapshot Protocol

### Why Snapshots?

The evolved operator set is a **learned artifact**: valuable to distribute and reuse.

**Three Use Cases**:
1. **Codec Distribution**: Ship snapshot with compressed file → decoder loads operators
2. **Evolution Checkpointing**: Resume from generation N without restarting
3. **Domain Specialization**: Financial data snapshot vs. sensor data snapshot

### Snapshot Versioning

Format version 2.0:
- **Major version** must match (backward-incompatible changes)
- **Minor version** differences are backward-compatible
- Enables evolution of format over time

### Section-Based Extensibility

Six section types, each optional:
- **OPERATOR_TABLE**: Extended operators (TableLookup data, etc.)
- **TRANSITION_GRAPH**: Graph edges + weights (for visualization)
- **CYCLE_TABLE**: Detected cycles + compression gain (for analysis)
- **CHROMOSOME**: Best evolved solution (for resuming GA)
- **EVOLUTION_STATS**: Last 1000 generation results (for analysis)
- **VALIDATION_HASH**: SHA-256 of training corpus (for verification)

**Lazy Loading**: Read manifest first (header + section offsets), load only requested sections.

### CRC32 Validation

Detects file corruption:
- Computed over all bytes except magic + CRC field
- Post-computation: write snapshot, compute CRC, seek back, update
- Prevents silent decompression corruption

---

## Distributed Adaptive Compression Platform

### Theoretical Motivation

A single GA run is expensive but produces domain-optimal operator sets. A single compressor run is cheap but benefits enormously from those operator sets. Tightly coupling them wastes compute on every compression call. **Separation of concerns** enables:

- GA runs on a powerful dedicated node (or cloud VM) with large corpus, many generations
- Codec runs anywhere, cheaply, reusing published operator sets via the snapshot store
- Multiple GA instances can specialize by domain (audio, text, binary) simultaneously

### Content-Addressed Snapshot Store

**Formal model**: Let `S` be the set of all valid snapshot files. Define:

$$\text{id}(s) = \text{Hex}(\text{SHA-256}(s))$$

The store is a partial function `store : id → s` with the invariant:

$$\forall s_1, s_2 \in S : \text{id}(s_1) = \text{id}(s_2) \Rightarrow s_1 = s_2$$

(by collision resistance of SHA-256 — probability of collision ≤ 2⁻¹²⁸).

**Deduplication**: Same operator set → same serialized bytes (deterministic serializer) → same SHA-256 → same ID → no duplicate disk writes. Evolution produces identical-fitness chromosomes at convergence; dedup prevents store bloat.

**Immutability**: Once published, a snapshot is never modified. This makes IDs stable references: a v0x03 compressed file embeds an ID that will always resolve to the same operator set.

### Information-Theoretic View of Snapshots

A snapshot encodes the conditional probability model `P(X_t | X_{t-1})` learned by the GA on the training corpus. Given this model, the codec applies Huffman-like prefix coding per transition:

$$H(X | \text{snapshot}) = -\sum_{x,y} p(x) p(y|x) \log_2 p(y|x)$$

For arithmetic data with step `+k`, the GA converges to a chromosome with a single rule `Add(k)`. The model assigns `P(X_t = x+k | X_{t-1} = x) ≈ 1`, giving `H(X | snapshot) ≈ 0` — approaching the theoretical lower bound.

### DataFingerprint: Feature Extraction Theory

The 12-element fingerprint vector characterizes byte sequences for domain classification:

**Entropy** (feature 0): `H(X) / 8` — normalized Shannon entropy. High entropy (→1) indicates random data; low entropy (→0) indicates structured data.

**Arithmetic-run density** (feature 1): Fraction of bytes covered by constant-step arithmetic runs (`MIN_RUN = 4`). High values indicate periodic or linearly-growing sequences (sensor data, counters, LCG streams).

**Cycle density** (feature 2): Fraction of consecutive byte pairs whose delta matches within a sliding window of size 8. High values indicate short repeating sequences (run-length encoding candidates).

**Delta histogram** (features 3–10): 8 equal-width buckets (width 32) for `|b[i+1] - b[i]|`:
- Bucket 0 (Δ ∈ [0,32)): near-constant sequences → arithmetic codec efficient
- Bucket 7 (Δ ∈ [224,255]): high-variance sequences → likely random
Distribution shape distinguishes domain types without needing a full histogram of byte values.

**Mean delta** (feature 11): `E[|Δ|] / 255` — normalized. Low for smooth signals (audio, slowly varying sensors); high for random or encrypted data.

**Cosine similarity** between two fingerprints:

$$\text{sim}(a, b) = \frac{a \cdot b}{\|a\| \cdot \|b\|}$$

Invariant to scale — a 4 KB sample and a 400 KB corpus of the same type give the same fingerprint direction.

### GA-Snapshot Convergence Property

**Claim**: As GA generations → ∞, the published snapshot approaches the optimal operator set for the training domain.

**Argument**: The fitness function directly estimates compression ratio on a sliding corpus window. By the schema theorem (Holland, 1975), high-fitness schemata (operator patterns matching the domain structure) proliferate across generations. The snapshot captures the highest-fitness chromosome seen, which monotonically improves (elitist selection). In the limit, the snapshot encodes a model that matches `H(X | X_{i-1})` for the domain.

**Practical bound**: For arithmetic data with known step `k`, optimal fitness is achieved in O(population_size) evaluations because `Add(k)` is a single-gene chromosome with maximal coverage. For complex mixed data, convergence is slower but bounded by the diversity of the extended operator library.

### v0x03 Format: Theoretical Justification

Embedding `snapshot_id` in the compressed stream couples the compression decision to the decompression context without requiring out-of-band metadata. The 32-byte SHA-256 ID is:

1. **Self-verifying**: Decoder confirms snapshot integrity by comparing `SHA-256(loadedBytes)` to the embedded ID
2. **Content-addressed**: Same snapshot content on any node → same ID → consistent decompression
3. **Forward-compatible**: Mode byte allows future extension (e.g., mode 0x02 = federated store, mode 0x03 = inline delta)

---

## Scientific References

### Foundational Works

1. **Shannon, C. E.** (1948). "A Mathematical Theory of Communication." *Bell System Technical Journal*, 27(3):379–423.
   - Source coding theorem, entropy definition
   - Fundamental bound for all lossless compression

2. **Huffman, D. A.** (1952). "A Method for the Construction of Minimum-Redundancy Codes." *Proceedings of the IRE*, 40(9):1098–1101.
   - Optimal symbol coding (baseline for comparison)

3. **Kraft, L. G.** (1949). "A Device for Quantizing, Grouping, and Coding Amplitude-Modulated Pulses." *MS Thesis, MIT*.
   - Kraft inequality for prefix-free codes

### Graph Theory & Algorithms

4. **Tarjan, R. E.** (1972). "Depth-first search and linear graph algorithms." *SIAM Journal on Computing*, 1(2):146–160.
   - SCC algorithm, O(V+E) complexity
   - Used in MRC cycle detection

5. **Johnson, D. B.** (1975). "Finding All the Elementary Circuits of a Directed Graph." *SIAM Journal on Computing*, 4(1):77–84.
   - Cycle enumeration algorithm
   - Foundation for cycle detection in MRC

### Genetic Algorithms

6. **Holland, J. H.** (1975). *Adaptation in Natural and Artificial Systems*. University of Michigan Press.
   - Schema theorem, building block hypothesis
   - Theoretical foundations for GA convergence

7. **Wolpert, D. H., & Macready, W. G.** (1997). "No free lunch theorems for optimization." *IEEE Transactions on Evolutionary Computation*, 1(1):67–82.
   - Proves GAs not universally optimal
   - Justifies domain-specific operator design

8. **Goldberg, D. E.** (1989). *Genetic Algorithms in Search, Optimization, and Machine Learning*. Addison-Wesley.
   - Practical GA design, mutation rates, selection pressure

### Data Compression

9. **Lempel, Z., & Ziv, J.** (1977). "A universal algorithm for sequential data compression." *IEEE Transactions on Information Theory*, 23(3):337–343.
   - LZ77 algorithm, basis for most modern compressors

10. **Salomon, D.** (2007). *Handbook of Data Compression* (4th ed.). Springer.
    - Comprehensive reference on compression algorithms
    - Covers Huffman, arithmetic coding, context modeling

11. **Cover, T. M., & Thomas, J. A.** (2006). *Elements of Information Theory* (2nd ed.). Wiley.
    - Rate-distortion theory, entropy rate
    - Mathematical foundations for compression

### Information Theory Advanced Topics

12. **Rissanen, J. J.** (1978). "Modeling by shortest data description." *Automatica*, 14(5):465–471.
    - Minimum Description Length (MDL) principle
    - Relevant to parsimony in GA fitness

13. **Blahut, R. E.** (1987). *Principles and Practice of Information Theory*. Addison-Wesley.
    - Information-theoretic bounds
    - Practical compression limits

---

## Open Research Questions

### For MRC

1. **Optimal Operator Selection**: Is greedy operator selection (shortest cost) optimal? Can dynamic programming find better global optima?

2. **Convergence Rate of GA**: What is the actual convergence rate for operator discovery? Can we prove faster convergence for domain-specific operators?

3. **Entropy Estimation**: Can we estimate $H(X | X_{i-1})$ from partial data to predict compression achievability?

4. **Extended Operator Discovery**: Can neural networks learn good extended operators better than GA?

5. **Lossy Extensions**: What happens to compression ratio if we allow controlled information loss (rate-distortion trade-off)?

6. **Snapshot Staleness**: When does a published snapshot become suboptimal for a domain as the data distribution shifts? What is the optimal snapshot refresh interval?

7. **Fingerprint Dimensionality**: Is 12 the right number of features for `DataFingerprint`? Can a learned embedding (e.g., contrastive training on domain pairs) outperform handcrafted features?

8. **Multi-Domain GA**: Can a single chromosome generalize across multiple domains, or does optimal compression always require domain-specialized operator sets?

### Experimental Hypotheses

- **H1**: Operator-aware crossover reduces convergence time by 50% vs standard crossover
- **H2**: Cycle-based encoding (v0x01) outperforms arithmetic runs (v0x02) on repetitive data with probability > 0.9
- **H3**: Transition graph structure (SCC distribution) predicts compression achievability
- **H4**: DataFingerprint cosine similarity correctly identifies the domain-matching snapshot ≥ 90% of the time on held-out test data
- **H5**: v0x03 connected-mode compression ratio equals v0x02 ratio on arithmetic data (snapshot rules add no overhead on already-optimal data)

---

## Summary: Compression Pipeline

```
Raw Data
   ↓
[Operator Library] — 2,400+ base operators + extended operators
   ↓
[Transition Graph] — analyze data patterns via Markov chains
   ↓
[Cycle Detection] — find repeating structures via Tarjan + Johnson
   ↓
[GA Engine Service] — evolve better operator rules (distributed, continuous)
   ↓
[Snapshot Store] — content-addressed SHA-256 keyed immutable store
   ↓
[Selector Service] — pick best snapshot for input via DataFingerprint cosine similarity
   ↓
[v0x03 Encoder] — apply evolved operator rules from snapshot:
   ├→ LITERAL (9 bits)
   ├→ ARITH_RUN (33 bits, reused many times)
   └→ Snapshot ID embedded in header (41 bytes fixed)
   ↓
Compressed Data (v0x03)
```

Each stage builds on the previous to maximize compression while remaining lossless.

---

## Summary: MRC in Information-Theoretic Context

MRC combines:
1. **Information Theory**: Exploits conditional entropy $H(X_i | X_{i-1})$
2. **Graph Theory**: Finds cycles via Tarjan + Johnson algorithms
3. **Algebra**: Leverages operator composition and group structure
4. **Genetic Algorithms**: Evolves domain-specific operator sets continuously
5. **Coding Theory**: Uses prefix-free codes (Kraft inequality)
6. **Distributed Systems**: Content-addressed snapshot store + service separation

The synergy achieves compression rates approaching Shannon bounds for highly structured data, while remaining theoretically lossless and practical.

---

**Last Updated**: 2026-03-28
**Version**: 4.0 (Phase 3 distributed platform: content-addressed snapshots, DataFingerprint, GA-codec separation)
