package tileworld.agent;

import sim.util.Int2D;
import tileworld.environment.TWEnvironment;

/**
 * TWAgent1 - "The Opportunistic Courier"
 * Strategy: Rapid Turnaround. Prefers holes as soon as it has 1 tile.
 */
public class TWAgent1 extends TWBaseAgent {

    public TWAgent1(String name, int xpos, int ypos, TWEnvironment env, double fuelLevel) {
        super(name, xpos, ypos, env, fuelLevel);
    }

    @Override
    protected TWThought executeWorkerLogic() {
        Int2D bestTarget = null;
        int bestScore = Integer.MIN_VALUE;
        TWAction bestAction = null;

        if (this.carriedTiles.size() < 3) {
            for (Int2D tile : this.getLowerPriorityTilesFromConsensusMemory()) {
                int score = standardizedScoreForTile(tile.x, tile.y);
                if (this.carriedTiles.size() > 0) score -= 150; // Penalty for hoarding

                if (score > bestScore && isBestCandidate(tile.x, tile.y, score, this.getEnvironment().getMessages(), TWAction.PICKUP)) {
                    bestScore = score; bestTarget = tile; bestAction = TWAction.PICKUP;
                }
            }
        }

        if (this.carriedTiles.size() > 0) {
            for (Int2D hole : this.getLowerPriorityHolesFromConsensusMemory()) {
                int score = standardizedScoreForHole(hole.x, hole.y);
                score += 150; // Boost for rapid offload

                if (score > bestScore && isBestCandidate(hole.x, hole.y, score, this.getEnvironment().getMessages(), TWAction.PUTDOWN)) {
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
}
