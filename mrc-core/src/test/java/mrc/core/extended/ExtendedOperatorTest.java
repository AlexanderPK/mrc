package mrc.core.extended;

import mrc.core.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Phase 2 Pillar 1: Extended Operator Space.
 */
public class ExtendedOperatorTest {

    // -------------------------------------------------------------------------
    // FunctionOperator tests
    // -------------------------------------------------------------------------

    @Test
    public void testPolynomialBasic() {
        FunctionOperator.Polynomial p = new FunctionOperator.Polynomial(2, 3);
        // (2*5*5 + 3) & 0xFF = 53
        assertEquals(53, p.apply(5));
        assertEquals(32, p.opId());
        assertEquals(8, p.operandBits());
        assertTrue(p.toExpression("x").contains("x^2"));
    }

    @Test
    public void testPolynomialWrapAround() {
        FunctionOperator.Polynomial p = new FunctionOperator.Polynomial(3, 10);
        // (3*100*100 + 10) & 0xFF = 30010 & 0xFF = 0x7550 & 0xFF = 0x50 = 80
        assertEquals((3 * 100 * 100 + 10) & 0xFF, p.apply(100));
    }

    @Test
    public void testLinearCongruential() {
        FunctionOperator.LinearCongruential lc = new FunctionOperator.LinearCongruential(5, 7);
        // (5*10 + 7) & 0xFF = 57
        assertEquals(57, lc.apply(10));
        assertEquals(33, lc.opId());
        assertEquals(8, lc.operandBits());
    }

    @Test
    public void testRotateLeft() {
        FunctionOperator.RotateLeft rl = new FunctionOperator.RotateLeft(1);
        // 0b10000001 = 0x81, rotl(1) = 0b00000011 = 0x03
        assertEquals(0x03, rl.apply(0x81));
        assertEquals(35, rl.opId());
        assertEquals(3, rl.operandBits());

        // 0b11110000 = 0xF0, rotl(4) = 0b00001111 = 0x0F
        FunctionOperator.RotateLeft rl4 = new FunctionOperator.RotateLeft(4);
        assertEquals(0x0F, rl4.apply(0xF0));
    }

    @Test
    public void testRotateRight() {
        FunctionOperator.RotateRight rr = new FunctionOperator.RotateRight(1);
        // 0b00000011 = 0x03, rotr(1) = 0b10000001 = 0x81
        assertEquals(0x81, rr.apply(0x03));
        assertEquals(36, rr.opId());

        // Rotate left then right should be identity
        FunctionOperator.RotateLeft rl2 = new FunctionOperator.RotateLeft(2);
        FunctionOperator.RotateRight rr2 = new FunctionOperator.RotateRight(2);
        for (int x = 0; x < 256; x++) {
            assertEquals(x, rr2.apply(rl2.apply(x)) & 0xFF,
                    "rotl(2) then rotr(2) should be identity for x=" + x);
        }
    }

    @Test
    public void testBitReverse() {
        FunctionOperator.BitReverse br = new FunctionOperator.BitReverse();
        // 0b10000001 = 0x81 → reversed = 0b10000001 = 0x81 (palindrome)
        assertEquals(0x81, br.apply(0x81));
        // 0b11110000 = 0xF0 → reversed = 0b00001111 = 0x0F
        assertEquals(0x0F, br.apply(0xF0));
        // Applying twice should be identity
        for (int x = 0; x < 256; x++) {
            assertEquals(x, br.apply(br.apply(x)) & 0xFF);
        }
        assertEquals(37, br.opId());
        assertEquals(0, br.operandBits());
    }

    @Test
    public void testNibbleSwap() {
        FunctionOperator.NibbleSwap ns = new FunctionOperator.NibbleSwap();
        // 0xAB → 0xBA
        assertEquals(0xBA, ns.apply(0xAB));
        // 0x12 → 0x21
        assertEquals(0x21, ns.apply(0x12));
        // Applying twice should be identity
        for (int x = 0; x < 256; x++) {
            assertEquals(x, ns.apply(ns.apply(x)) & 0xFF);
        }
        assertEquals(38, ns.opId());
        assertEquals(0, ns.operandBits());
    }

    @Test
    public void testDeltaOfDelta() {
        FunctionOperator.DeltaOfDelta d2 = new FunctionOperator.DeltaOfDelta();
        // apply(x) with no context: returns x
        assertEquals(10, d2.apply(10));
        // apply(x, context): (x - context) & 0xFF
        assertEquals(5, d2.apply(10, 5));
        assertEquals(251, d2.apply(5, 10));  // (5-10) & 0xFF = 251
        assertEquals(39, d2.opId());
    }

    @Test
    public void testTableLookup() {
        byte[] table = new byte[256];
        for (int i = 0; i < 256; i++) table[i] = (byte) (255 - i);
        FunctionOperator.TableLookup tl = new FunctionOperator.TableLookup(table);
        assertEquals(255, tl.apply(0));
        assertEquals(0,   tl.apply(255));
        assertEquals(127, tl.apply(128));
        assertEquals(34,  tl.opId());
    }

    // -------------------------------------------------------------------------
    // SuperfunctionOperator tests
    // -------------------------------------------------------------------------

    @Test
    public void testIterated() {
        Add add3 = new Add(3);
        SuperfunctionOperator.Iterated iter = new SuperfunctionOperator.Iterated(add3, 4);
        // add3 applied 4 times to 10: 10+3+3+3+3 = 22
        assertEquals(22, iter.apply(10));
        // Wrap-around: add3 applied 100 times to 0: (3*100) & 0xFF = 44
        assertEquals((3 * 100) & 0xFF, new SuperfunctionOperator.Iterated(add3, 100).apply(0));
        assertEquals(64, iter.opId());
    }

    @Test
    public void testFixedPointReach() {
        // Not(Not(x)) = x, so Not reaches fixed point in 2 steps... but Not(x)!=x for most x
        // Use XorOp(0): f(x) = x, immediate fixed point
        XorOp identity = new XorOp(0);
        SuperfunctionOperator.FixedPointReach fp = new SuperfunctionOperator.FixedPointReach(identity, 10);
        assertEquals(42, fp.apply(42));

        // Add(0) is identity — fixed point immediately
        Add add0 = new Add(0);
        SuperfunctionOperator.FixedPointReach fp2 = new SuperfunctionOperator.FixedPointReach(add0, 100);
        assertEquals(77, fp2.apply(77));

        assertEquals(65, fp.opId());
    }

    @Test
    public void testConjugate() {
        // Conjugate Not by Add(1): h(x) = x+1, f = Not, h_inv(y) = y-1
        Not not = new Not();
        Add add1 = new Add(1);
        SuperfunctionOperator.Conjugate conj = new SuperfunctionOperator.Conjugate(not, add1);
        // h(5) = 6, not(6) = 249, h_inv(249) = 248
        int expected = (((~(5 + 1)) & 0xFF) - 1) & 0xFF;
        assertEquals(expected, conj.apply(5));
        assertEquals(66, conj.opId());
    }

    // -------------------------------------------------------------------------
    // CompositeOperator tests
    // -------------------------------------------------------------------------

    @Test
    public void testCompositeBasic() {
        Add add5 = new Add(5);
        Mul mul2 = new Mul(2);
        CompositeOperator comp = new CompositeOperator(add5, mul2);
        // (10 + 5) * 2 = 30
        assertEquals(30, comp.apply(10));
        assertEquals(40, comp.opId());
    }

    @Test
    public void testCompositeChain() {
        CompositeOperator comp = new CompositeOperator(
                new Add(1), new Add(2), new Add(3), new Add(4));
        // x + 1 + 2 + 3 + 4 = x + 10
        assertEquals(20, comp.apply(10));
    }

    @Test
    public void testCompositeRequiresTwoSteps() {
        assertThrows(IllegalArgumentException.class,
                () -> new CompositeOperator(List.of(new Add(1))));
        assertThrows(IllegalArgumentException.class,
                () -> new CompositeOperator(List.of(new Add(1), new Add(2), new Add(3), new Add(4), new Add(5))));
    }

    @Test
    public void testCompositeOptimizedAddFusion() {
        CompositeOperator comp = new CompositeOperator(new Add(10), new Add(20));
        Operator opt = comp.optimized();
        // Should fuse into Add(30)
        assertInstanceOf(Add.class, opt);
        assertEquals(30, opt.apply(0));
    }

    @Test
    public void testCompositeOptimizedXorFusion() {
        CompositeOperator comp = new CompositeOperator(new XorOp(0b10101010), new XorOp(0b11001100));
        Operator opt = comp.optimized();
        assertInstanceOf(XorOp.class, opt);
        int expected = 0b10101010 ^ 0b11001100;
        assertEquals(expected, opt.apply(0));
    }

    @Test
    public void testCompositeOptimizedNotCancellation() {
        CompositeOperator comp = new CompositeOperator(new Not(), new Not());
        Operator opt = comp.optimized();
        // Not ∘ Not = identity
        for (int x = 0; x < 256; x++) {
            assertEquals(x, opt.apply(x) & 0xFF, "Identity for x=" + x);
        }
    }

    @Test
    public void testCompositeOptimizedAddSubCancel() {
        // Add(5) ∘ Sub(5) should cancel to identity
        CompositeOperator comp = new CompositeOperator(new Add(5), new Sub(5));
        Operator opt = comp.optimized();
        for (int x = 0; x < 256; x++) {
            assertEquals(x, opt.apply(x) & 0xFF, "Identity for x=" + x);
        }
    }

    // -------------------------------------------------------------------------
    // OperatorCostModel tests
    // -------------------------------------------------------------------------

    @Test
    public void testCostModelLiteral() {
        assertEquals(8, OperatorCostModel.LITERAL_BITS);
        assertEquals(9, OperatorCostModel.LITERAL_TOKEN_BITS);
    }

    @Test
    public void testRelationalTokenCost() {
        // Not: 5-bit opId + 0 operand bits + 1 flag = 6
        assertEquals(6, OperatorCostModel.relationalTokenCost(new Not()));
        // Add: 5-bit opId + 8 operand bits + 1 flag = 14
        assertEquals(14, OperatorCostModel.relationalTokenCost(new Add(5)));
        // ShiftLeft: 5-bit opId + 3 operand bits + 1 flag = 9
        assertEquals(9, OperatorCostModel.relationalTokenCost(new ShiftLeft(2)));
    }

    @Test
    public void testArithRunBreakEven() {
        assertEquals(4, OperatorCostModel.arithRunBreakEven());
        // runLen=4: 4*9=36 > 33 → savings > 0
        assertTrue(OperatorCostModel.estimatedArithRunSavings(4) > 0);
        // runLen=3: 3*9=27 < 33 → savings < 0
        assertTrue(OperatorCostModel.estimatedArithRunSavings(3) < 0);
    }

    @Test
    public void testIsBetterThanLiteral() {
        assertTrue(OperatorCostModel.isBetterThanLiteral(new Not())); // 6 < 8
        assertFalse(OperatorCostModel.isBetterThanLiteral(new Add(5))); // 14 >= 8
    }

    // -------------------------------------------------------------------------
    // ExtendedOperatorLibrary tests
    // -------------------------------------------------------------------------

    @Test
    public void testExtendedLibraryInstantiation() {
        ExtendedOperatorLibrary lib = ExtendedOperatorLibrary.getInstance();
        assertNotNull(lib);
        assertFalse(lib.extendedOperators().isEmpty());
    }

    @Test
    public void testExtendedLibraryContainsRotations() {
        ExtendedOperatorLibrary lib = ExtendedOperatorLibrary.getInstance();
        long rotateCount = lib.extendedOperators().stream()
                .filter(op -> op instanceof FunctionOperator.RotateLeft
                           || op instanceof FunctionOperator.RotateRight)
                .count();
        assertEquals(14, rotateCount); // 7 left + 7 right
    }

    @Test
    public void testFindShortestExtendedFallsBackToBase() {
        ExtendedOperatorLibrary lib = ExtendedOperatorLibrary.getInstance();
        // Not(0xFF) = 0x00 — base Not should be found
        Optional<Operator> op = lib.findShortestExtended(0xFF, 0x00);
        assertTrue(op.isPresent());
        assertEquals(0x00, op.get().apply(0xFF) & 0xFF);
    }

    @Test
    public void testFindShortestExtendedFindsRotation() {
        ExtendedOperatorLibrary lib = ExtendedOperatorLibrary.getInstance();
        // RotateLeft(4): 0xF0 → 0x0F
        Optional<Operator> op = lib.findShortestExtended(0xF0, 0x0F);
        assertTrue(op.isPresent());
        assertEquals(0x0F, op.get().apply(0xF0) & 0xFF);
    }
}
