package tileworld.agent;

import java.util.Map;
import sim.util.Int2D;
import tileworld.environment.TWEnvironment;

/**
 * TWAgent5
 */
public class TWAgent5 extends TWBaseAgent {

    // --- CONSTANTS ---

    // --- CONSTRUCTORS ---

    /**
     * Constructs a new TWAgent5.
     *
     * @param name      The unique identifier for this agent.
     * @param xpos      The starting X coordinate.
     * @param ypos      The starting Y coordinate.
     * @param env       The Tileworld environment.
     * @param fuelLevel The starting fuel level.
     */
    public TWAgent5(String name, int xpos, int ypos, TWEnvironment env, double fuelLevel) {
        super(name, xpos, ypos, env, fuelLevel);
    }

    // --- CORE BEHAVIOR ---

    /**
     * Implements the specific worker logic for this agent type.
     * This is only called if the following higher-priority tasks are satisfied:
     * 1. Consensus Memory Sync (Broadcasted Messages)
     * 2. Locating Fuel Station
     * 3. Fuel Level Safety Check
     * 4. Object Lifetime Monitoring (First Responder)
     * 5. Automatically-Handled High-Priority Tasks (Reflex)
     * * @return TWThought The decided action and direction for this step.
     */
    @Override
    protected TWThought executeWorkerLogic() {
        ConsensusMemory memory = (ConsensusMemory) this.getMemory();
        int currentStep = (int) this.getEnvironment().schedule.getSteps();
        int knownLifetime = memory.getKnownLifetime();

        Int2D bestTarget = null;
        TWAction bestAction = null;
        double bestSlack = Double.MAX_VALUE;
        double bestDistance = Double.MAX_VALUE;

        if (this.carriedTiles.size() < 3) {
            for (Map.Entry<Int2D, Integer> entry : memory.getConsensusTiles().entrySet()) {
                Int2D target = entry.getKey();
                Integer creationTime = entry.getValue();

                double dist = getStandardizedDistance(this.getX(), this.getY(), target.x, target.y);
                if (dist == Double.MAX_VALUE) continue;

                double slack = (creationTime + knownLifetime) - (currentStep + dist);
                if (slack < 0) continue;

                int standardScore = standardizedScoreForTile(target.x, target.y);
                if (standardScore == Integer.MIN_VALUE) continue;
                if (!isBestCandidate(target.x, target.y, standardScore, this.getEnvironment().getMessages(), TWAction.PICKUP)) {
                    continue;
                }

                if (slack < bestSlack || (slack == bestSlack && dist < bestDistance)) {
                    bestSlack = slack;
                    bestDistance = dist;
                    bestTarget = target;
                    bestAction = TWAction.PICKUP;
                }
            }
        }

        if (this.carriedTiles.size() > 0) {
            for (Map.Entry<Int2D, Integer> entry : memory.getConsensusHoles().entrySet()) {
                Int2D target = entry.getKey();
                Integer creationTime = entry.getValue();

                double dist = getStandardizedDistance(this.getX(), this.getY(), target.x, target.y);
                if (dist == Double.MAX_VALUE) continue;

                double slack = (creationTime + knownLifetime) - (currentStep + dist);
                if (slack < 0) continue;

                int standardScore = standardizedScoreForHole(target.x, target.y);
                if (standardScore == Integer.MIN_VALUE) continue;
                if (!isBestCandidate(target.x, target.y, standardScore, this.getEnvironment().getMessages(), TWAction.PUTDOWN)) {
                    continue;
                }

                if (slack < bestSlack || (slack == bestSlack && dist < bestDistance)) {
                    bestSlack = slack;
                    bestDistance = dist;
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
