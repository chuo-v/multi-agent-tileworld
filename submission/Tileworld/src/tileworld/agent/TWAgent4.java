package tileworld.agent;

import java.awt.Rectangle;
import java.util.List;
import sim.util.Int2D;
import tileworld.environment.TWEnvironment;

/**
 * TWAgent4 - "The Zone Localizer"
 *
 * Strategy: Strict zone-based coverage. Operates primarily within its
 * assigned zone to eliminate inter-agent competition and redundant travel.
 *
 *
 */
public class TWAgent4 extends TWBaseAgent {

    private static final double ZONE_REFUEL_THRESHOLD = 300.0;
    private static final int    ZONE_REFUEL_DIST      = 15;

    public TWAgent4(String name, int xpos, int ypos, TWEnvironment env, double fuelLevel) {
        super(name, xpos, ypos, env, fuelLevel);
    }

    @Override
    protected TWThought executeWorkerLogic() {
        Rectangle myZone = getMyZone();

        // --- PROACTIVE REFUEL (cheap if station is nearby) ---
        Int2D station = getFuelStationFromConsensusMemory();
        if (station != null && station.x != -1 && this.fuelLevel < ZONE_REFUEL_THRESHOLD) {
            double distToStation = getStandardizedDistance(this.getX(), this.getY(), station.x, station.y);
            if (distToStation <= ZONE_REFUEL_DIST) {
                this.explorationTarget = null;
                return executePathTo(station.x, station.y, TWAction.REFUEL);
            }
        }

        List<Int2D> tiles = getLowerPriorityTilesFromConsensusMemory();
        List<Int2D> holes = getLowerPriorityHolesFromConsensusMemory();

        // Classify what exists in our zone
        boolean zoneHasTiles = false;
        boolean zoneHasHoles = false;
        if (myZone != null) {
            for (Int2D t : tiles) { if (myZone.contains(t.x, t.y)) { zoneHasTiles = true; break; } }
            for (Int2D h : holes) { if (myZone.contains(h.x, h.y)) { zoneHasHoles = true; break; } }
        }

        boolean zoneIsEmpty    = (myZone != null) && !zoneHasTiles && !zoneHasHoles;
        // BUG FIX: carrying tiles but no in-zone holes → must search globally for delivery
        boolean needGlobalHoles = (this.carriedTiles.size() > 0) && !zoneHasHoles;

        Int2D    bestTarget = null;
        int      bestScore  = Integer.MIN_VALUE;
        TWAction bestAction = null;

        // --- PICKUP: zone-filtered ---
        if (this.carriedTiles.size() < 3) {
            for (Int2D tile : tiles) {
                // Hard filter: skip out-of-zone tiles (unless zone is empty)
                if (!zoneIsEmpty && myZone != null && !myZone.contains(tile.x, tile.y)) continue;
                int score = standardizedScoreForTile(tile.x, tile.y);
                if (score == Integer.MIN_VALUE) continue;
                if (score > bestScore && isBestCandidate(tile.x, tile.y, score,
                        this.getEnvironment().getMessages(), TWAction.PICKUP)) {
                    bestScore = score; bestTarget = tile; bestAction = TWAction.PICKUP;
                }
            }
        }

        // --- DELIVERY: zone-filtered OR global fallback ---
        if (this.carriedTiles.size() > 0) {
            for (Int2D hole : holes) {
                // Allow global holes if zone has none (carrying-stuck bug fix)
                if (!zoneIsEmpty && !needGlobalHoles && myZone != null && !myZone.contains(hole.x, hole.y)) continue;
                int score = standardizedScoreForHole(hole.x, hole.y);
                if (score == Integer.MIN_VALUE) continue;
                if (score > bestScore && isBestCandidate(hole.x, hole.y, score,
                        this.getEnvironment().getMessages(), TWAction.PUTDOWN)) {
                    bestScore = score; bestTarget = hole; bestAction = TWAction.PUTDOWN;
                }
            }
        }

        if (bestTarget != null) {
            this.explorationTarget = null;
            return executePathTo(bestTarget.x, bestTarget.y, bestAction);
        }
        return executeExploreMove();
    }

    /** Safely retrieves assigned zone by casting this.memory (protected in TWAgent). */
    private Rectangle getMyZone() {
        try {
            return ((ConsensusMemory) this.memory).getAssignedZone();
        } catch (Exception e) {
            return null;
        }
    }
}