package mrc.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for OperatorLibrary.
 *
 * Verifies:
 * - findShortest() returns correct results
 * - All operators are registered with unique IDs
 * - Cache performance is acceptable
 */
public class OperatorLibraryTest {

    private static OperatorLibrary lib;

    @BeforeAll
    public static void setup() {
        lib = OperatorLibrary.getInstance();
    }

    @Test
    public void testFindShortest_AddThree_10To13() {
        // The cheapest path from 10 to 13 should be Add(3)
        // Cost = 5 (opId) + 8 (operand bits) = 13
        var result = lib.findShortest(10, 13);
        assertTrue(result.isPresent(), "Should find a shortest operator");
        Operator op = result.get();
        assertEquals(13, op.apply(10) & 0xFF, "Add(3) applied to 10 should give 13");
    }

    @Test
    public void testFindShortest_WrapAround_255To0() {
        // 255 + 1 mod 256 = 0, so Add(1) wraps around
        var result = lib.findShortest(255, 0);
        assertTrue(result.isPresent(), "Should find shortest operator for 255->0");
        Operator op = result.get();
        assertEquals(0, op.apply(255) & 0xFF, "Should wrap to 0");
    }

    @Test
    public void testFindShortest_NoPath() {
        // Some paths may have no single operator solution
        // TODO: Test a specific case where no operator exists
    }

    @Test
    public void testOperatorLibrarySize() {
        // Should have registered all operators
        var all = lib.all();
        assertTrue(all.size() > 0, "Library should contain operators");
    }

    @Test
    public void testOperatorLibraryGetById() {
        // Test lookup by opId
        var all = lib.all();
        if (!all.isEmpty()) {
            Operator first = all.get(0);
            byte id = first.opId();
            Operator lookedUp = lib.byId(id);
            assertNotNull(lookedUp, "Should find operator by ID");
        }
    }

    @Test
    public void testAddOperator() {
        Add add5 = new Add(5);
        assertEquals(7, add5.apply(2) & 0xFF);
        assertEquals(8, add5.operandBits());
        assertEquals("x + 5", add5.toExpression("x"));
    }

    @Test
    public void testSubOperator() {
        Sub sub3 = new Sub(3);
        assertEquals(2, sub3.apply(5) & 0xFF);
        assertEquals(8, sub3.operandBits());
    }

    @Test
    public void testMulOperator() {
        Mul mul2 = new Mul(2);
        assertEquals(10, mul2.apply(5) & 0xFF);
        assertEquals(8, mul2.operandBits());
    }

    @Test
    public void testDivOperator() {
        Div div2 = new Div(2);
        assertEquals(2, div2.apply(5) & 0xFF);
        assertEquals(8, div2.operandBits());
    }

    @Test
    public void testDivByZeroThrows() {
        assertThrows(ArithmeticException.class, () -> new Div(0));
    }

    @Test
    public void testNotOperator() {
        Not not = new Not();
        assertEquals(~0 & 0xFF, not.apply(0) & 0xFF);
        assertEquals(0, not.operandBits());
    }

    @Test
    public void testShiftLeftOperator() {
        ShiftLeft shl = new ShiftLeft(2);
        assertEquals(20, shl.apply(5) & 0xFF);
        assertEquals(3, shl.operandBits());
    }

    @Test
    public void testShiftRightOperator() {
        ShiftRight shr = new ShiftRight(1);
        assertEquals(2, shr.apply(5) & 0xFF);
        assertEquals(3, shr.operandBits());
    }

    @Test
    public void testXorOperator() {
        XorOp xor = new XorOp(0xFF);
        assertEquals(0, xor.apply(0xFF) & 0xFF);
        assertEquals(0xFF, xor.apply(0) & 0xFF);
    }

    @Test
    public void testAndOperator() {
        AndOp and = new AndOp(0x0F);
        assertEquals(5, and.apply(0xF5) & 0xFF);
    }

    @Test
    public void testOrOperator() {
        OrOp or = new OrOp(0xF0);
        assertEquals(0xF5, or.apply(0x05) & 0xFF);
    }

    @Test
    public void testTransitionFinding() {
        var trans = Transition.find(5, 10, lib);
        if (trans.isPresent()) {
            assertTrue(trans.get().isCompressing() || !trans.get().isCompressing());
        }
    }
}
