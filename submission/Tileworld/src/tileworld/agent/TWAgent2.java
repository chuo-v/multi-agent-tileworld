package tileworld.agent;

import java.util.List;
import sim.util.Int2D;
import tileworld.environment.TWEnvironment;

/**
 * TWAgent2 - "The Hoarder"
 * Strategy: Max Capacity Collector. Prioritizes filling inventory before dropping off.
 */
public class TWAgent2 extends TWBaseAgent {

    public TWAgent2(String name, int xpos, int ypos, TWEnvironment env, double fuelLevel) {
        super(name, xpos, ypos, env, fuelLevel);
    }

    @Override
    protected TWThought executeWorkerLogic() {
        Int2D bestTarget = null;
        int bestScore = Integer.MIN_VALUE;
        TWAction bestAction = null;

        // Hoarder policy: keep collecting until inventory reaches max capacity.
        if (this.carriedTiles.size() < 3) {
            List<Int2D> tileCandidates = getLowerPriorityTilesFromConsensusMemory();
            for (Int2D target : tileCandidates) {
                int score = standardizedScoreForTile(target.x, target.y);
                if (score > bestScore && isBestCandidate(target.x, target.y, score, this.getEnvironment().getMessages(),
                        TWAction.PICKUP)) {
                    bestScore = score;
                    bestTarget = target;
                    bestAction = TWAction.PICKUP;
                }
            }
        }

        // Only when full do we commit to hole drop-off targets.
        if (this.carriedTiles.size() == 3) {
            List<Int2D> holeCandidates = getLowerPriorityHolesFromConsensusMemory();
            for (Int2D target : holeCandidates) {
                int score = standardizedScoreForHole(target.x, target.y);
                if (score > bestScore && isBestCandidate(target.x, target.y, score, this.getEnvironment().getMessages(),
                        TWAction.PUTDOWN)) {
                    bestScore = score;
                    bestTarget = target;
                    bestAction = TWAction.PUTDOWN;
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
