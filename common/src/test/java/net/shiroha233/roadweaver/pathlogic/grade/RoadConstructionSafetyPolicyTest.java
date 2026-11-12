package net.shiroha233.roadweaver.pathlogic.grade;

/** Covers the 6-8 block short hillside fill allowance used at real road joins. */
public final class RoadConstructionSafetyPolicyTest {
    private RoadConstructionSafetyPolicyTest() {}

    public static void main(String[] args) {
        require(RoadConstructionSafety.isExtendedCenterGrade(0, 6),
                "six-block center fill should be an allowed short hillside grade");
        require(RoadConstructionSafety.isExtendedCenterGrade(0, 8),
                "eight-block center fill should be an allowed short hillside grade");
        require(!RoadConstructionSafety.isExtendedCenterGrade(0, 9),
                "nine-block center fill must remain blocked");
        require(RoadConstructionSafety.isExtendedCenterGrade(6, 0),
                "six-block center cut should remain allowed");
        require(RoadConstructionSafety.isCrossSectionFillAllowed(0, 2, 5),
                "a five-block side-column fill under a two-block centre fill must be allowed");
        require(!RoadConstructionSafety.isCrossSectionFillAllowed(0, 2, 6),
                "a six-block side-column fill without an extended centre grade must remain blocked");
        require(!RoadConstructionSafety.isCrossSectionFillAllowed(0, 2, 9),
                "a nine-block side-column fill must remain blocked");
        require(RoadConstructionSafety.isCrossSectionFillAllowed(0, 3, 5),
                "a five-block side-column fill under an extended centre grade must be allowed");
        System.out.println("RoadConstructionSafetyPolicyTest: PASS");
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
