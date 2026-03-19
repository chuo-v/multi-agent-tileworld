package tileworld.agent;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import sim.util.Int2D;
import tileworld.environment.TWDirection;
import tileworld.environment.TWEntity;
import tileworld.environment.TWEnvironment;
import tileworld.environment.TWFuelStation;
import tileworld.environment.TWHole;
import tileworld.Parameters;
import tileworld.environment.TWObject;
import tileworld.environment.TWObstacle;
import tileworld.environment.TWTile;
import tileworld.exceptions.CellBlockedException;
import tileworld.planners.ConsensusPathGenerator;
import tileworld.planners.TWPath;
import tileworld.planners.TWPathStep;

public abstract class TWBaseAgent extends TWAgent {

    protected static final int BASE_SCORE_OFFSET = 100000;
    protected static final int AUTOMATIC_SCORE_THRESHOLD = BASE_SCORE_OFFSET - 200;

    protected static final int DISTANCE_WEIGHT = 10;
    protected static final int URGENCY_WEIGHT = 1;
    protected static final int INVENTORY_WEIGHT = 20;

    private String name;
    private ConsensusMemory consensusMemory;
    private ConsensusPathGenerator pathGenerator;

    protected Int2D explorationTarget = null;
    protected TWEntity monitoringTarget = null;
    private final int fuelSafetyBuffer;
    private boolean lastActionFailed = false;

    private Map<String, Double> distanceCache = new HashMap<>();

    public TWBaseAgent(String name, int xpos, int ypos, TWEnvironment env, double fuelLevel) {
        super(xpos, ypos, env, fuelLevel);
        this.name = name;
        this.memory = new ConsensusMemory(this, env.schedule, env.getxDimension(), env.getyDimension());
        this.consensusMemory = (ConsensusMemory) this.memory;
        this.fuelSafetyBuffer = (int)(0.4 * (env.getxDimension() + env.getyDimension()));
    }

    @Override
    protected final TWThought think() {
        distanceCache.clear();

        ArrayList<Message> messages = this.getEnvironment().getMessages();
        this.consensusMemory.updateState(messages, (int)this.getEnvironment().schedule.getSteps());

        if (lastActionFailed) {
            lastActionFailed = false;
            this.explorationTarget = null;
            this.monitoringTarget = null;
        }

        this.consensusMemory.performZoneAssignmentIfNecessary();
        Int2D station = this.consensusMemory.getConsensusFuelStation();

        TWThought immediateThought = checkImmediateFreeLunch();
        if (immediateThought != null) return immediateThought;

        if (station.x == -1) return executeExploreMove();

        int mapDimensionSum = this.getEnvironment().getxDimension() + this.getEnvironment().getyDimension();
        int opportunisticFuelThreshold = mapDimensionSum / 2; 
        int opportunisticDistMargin = mapDimensionSum / 20; 
        if (station.x != -1 && this.fuelLevel < Parameters.defaultFuelLevel - opportunisticFuelThreshold) {
            double distToStation = getStandardizedDistance(this.getX(), this.getY(), station.x, station.y);
            if (distToStation <= opportunisticDistMargin) { 
                return executePathTo(station.x, station.y, TWAction.REFUEL);
            }
        }

        if (this.fuelLevel < getFuelSafetyThreshold()) {
            this.explorationTarget = null;
            double pathCost = getStandardizedDistance(this.getX(), this.getY(), station.x, station.y);
            if (pathCost > this.fuelLevel) {
                return new TWThought(TWAction.MOVE, TWDirection.Z);
            }
            return executePathTo(station.x, station.y, TWAction.REFUEL);
        }

        if (!consensusMemory.isDefiniteLifetimeKnown()) {
            TWThought monitoringThought = checkMonitoringTasks();
            if (monitoringThought != null) return monitoringThought;
        } else {
            this.monitoringTarget = null;
        }

        TWThought automaticThought = executeAutomaticHighPriorityLogic();
        if (automaticThought != null) return automaticThought;

        return executeWorkerLogic();
    }

    private TWThought checkImmediateFreeLunch() {
        int cx = this.getX();
        int cy = this.getY();

        if (this.carriedTiles.size() < 3 && consensusMemory.getConsensusTiles().containsKey(new Int2D(cx, cy))) {
            int score = standardizedScoreForTile(cx, cy);
            if (score != Integer.MIN_VALUE && isBestCandidate(cx, cy, score, this.getEnvironment().getMessages(), TWAction.PICKUP)) {
                return new TWThought(TWAction.PICKUP, TWDirection.Z);
            }
        }

        if (this.carriedTiles.size() > 0 && consensusMemory.getConsensusHoles().containsKey(new Int2D(cx, cy))) {
            int score = standardizedScoreForHole(cx, cy);
            if (score != Integer.MIN_VALUE && isBestCandidate(cx, cy, score, this.getEnvironment().getMessages(), TWAction.PUTDOWN)) {
                return new TWThought(TWAction.PUTDOWN, TWDirection.Z);
            }
        }
        return null;
    }

    private TWThought executeAutomaticHighPriorityLogic() {
        Int2D bestTarget = null;
        int bestScore = Integer.MIN_VALUE;
        TWAction bestAction = null;

        if (this.carriedTiles.size() < 3) {
            for (Int2D tilePos : consensusMemory.getConsensusTiles().keySet()) {
                int score = standardizedScoreForTile(tilePos.x, tilePos.y);
                if (score > AUTOMATIC_SCORE_THRESHOLD && score > bestScore) {
                    if (isBestCandidate(tilePos.x, tilePos.y, score, this.getEnvironment().getMessages(), TWAction.PICKUP)) {
                        bestScore = score;
                        bestTarget = tilePos;
                        bestAction = TWAction.PICKUP;
                    }
                }
            }
        }

        if (this.carriedTiles.size() > 0) {
            for (Int2D holePos : consensusMemory.getConsensusHoles().keySet()) {
                int score = standardizedScoreForHole(holePos.x, holePos.y);
                if (score > AUTOMATIC_SCORE_THRESHOLD && score > bestScore) {
                    if (isBestCandidate(holePos.x, holePos.y, score, this.getEnvironment().getMessages(), TWAction.PUTDOWN)) {
                        bestScore = score;
                        bestTarget = holePos;
                        bestAction = TWAction.PUTDOWN;
                    }
                }
            }
        }

        if (bestTarget != null) {
            this.explorationTarget = null;
            return executePathTo(bestTarget.x, bestTarget.y, bestAction);
        }
        return null;
    }

    public int standardizedScoreForTile(int x, int y) {
        return calculateStandardizedScore(x, y, TWAction.PICKUP);
    }

    public int standardizedScoreForHole(int x, int y) {
        return calculateStandardizedScore(x, y, TWAction.PUTDOWN);
    }

    protected int calculateStandardizedScore(int targetX, int targetY, TWAction action) {
        if (action == TWAction.PICKUP && this.carriedTiles.size() >= 3) return Integer.MIN_VALUE;
        if (action == TWAction.PUTDOWN && this.carriedTiles.size() == 0) return Integer.MIN_VALUE;

        int zonePenalty = 0;
        Rectangle myZone = consensusMemory.getAssignedZone();
        if (myZone != null && !myZone.contains(targetX, targetY)) {
            int mapDimensionSum = this.getEnvironment().getxDimension() + this.getEnvironment().getyDimension();
            zonePenalty = (mapDimensionSum / 2) * DISTANCE_WEIGHT; 
        }

        double rawDist = getStandardizedDistance(this.getX(), this.getY(), targetX, targetY);
        if (rawDist == Double.MAX_VALUE) return Integer.MIN_VALUE;
        int dist = (int) rawDist;

        int currentStep = (int)this.getEnvironment().schedule.getSteps();
        Integer creationTime = (action == TWAction.PICKUP) ?
            consensusMemory.getTileTimestamp(targetX, targetY) :
            consensusMemory.getHoleTimestamp(targetX, targetY);

        int slack = Integer.MAX_VALUE;
        if (creationTime != null) {
            int life = consensusMemory.getKnownLifetime();
            int deadline = creationTime + life;
            slack = deadline - (currentStep + dist);
            if (slack < 0) return Integer.MIN_VALUE;
        }

        int clusterBonus = 0;
        int clusterBonusMultiplier = ((this.getEnvironment().getxDimension() + this.getEnvironment().getyDimension()) / 20) * DISTANCE_WEIGHT; 
        
        if (action == TWAction.PICKUP) {
            int spaceAvailable = 3 - this.carriedTiles.size();
            int count = getClusterCount(targetX, targetY, consensusMemory.getConsensusTiles().keySet());
            clusterBonus = (count * clusterBonusMultiplier) + (spaceAvailable * 15);
        } else if (action == TWAction.PUTDOWN) {
            int tilesAvailable = this.carriedTiles.size();
            int count = getClusterCount(targetX, targetY, consensusMemory.getConsensusHoles().keySet());
            clusterBonus = (count * clusterBonusMultiplier) + (tilesAvailable * 15);
        }

        int score = BASE_SCORE_OFFSET - zonePenalty + clusterBonus;
        if (action == TWAction.PUTDOWN) {
             score += -(dist * DISTANCE_WEIGHT) + (this.carriedTiles.size() * INVENTORY_WEIGHT);
        } else {
             score += -(dist * DISTANCE_WEIGHT) - (this.carriedTiles.size() * INVENTORY_WEIGHT);
        }

        if (slack != Integer.MAX_VALUE) {
            score -= (slack * URGENCY_WEIGHT);
        }
        return score;
    }

    private int getClusterCount(int x, int y, java.util.Set<Int2D> others) {
        int radius = (this.getEnvironment().getxDimension() + this.getEnvironment().getyDimension()) / 20;
        int count = 0;
        for (Int2D pos : others) {
            if (pos.x == x && pos.y == y) continue;
            if (Math.abs(pos.x - x) + Math.abs(pos.y - y) <= radius) count++;
        }
        return Math.min(count, radius);
    }

    public List<Int2D> getLowerPriorityTilesFromConsensusMemory() {
        ArrayList<Int2D> list = new ArrayList<>();
        for (Int2D pos : consensusMemory.getConsensusTiles().keySet()) {
            int score = standardizedScoreForTile(pos.x, pos.y);
            if (score != Integer.MIN_VALUE && score <= AUTOMATIC_SCORE_THRESHOLD) {
                list.add(pos);
            }
        }
        return list;
    }

    public List<Int2D> getLowerPriorityHolesFromConsensusMemory() {
        ArrayList<Int2D> list = new ArrayList<>();
        for (Int2D pos : consensusMemory.getConsensusHoles().keySet()) {
            int score = standardizedScoreForHole(pos.x, pos.y);
            if (score != Integer.MIN_VALUE && score <= AUTOMATIC_SCORE_THRESHOLD) {
                list.add(pos);
            }
        }
        return list;
    }

    protected TWThought checkMonitoringTasks() {
        if (this.monitoringTarget != null) {
            if (consensusMemory.hasGlobalHigherPriorityMonitor(this.getName())) {
                System.out.println(this.getName() + " yielding monitor task.");
                this.monitoringTarget = null;
            }
        }

        if (this.monitoringTarget == null) {
            if (!consensusMemory.isAnyAgentMonitoring(this.getName())) {
                double expectedTaskDuration = this.consensusMemory.getKnownLifetime();
                double requiredFuel = getFuelSafetyThreshold() + (expectedTaskDuration * 1.1);

                if (this.fuelLevel > requiredFuel) {
                    ArrayList<TWEntity> createdObjects = this.consensusMemory.getCreatedObjectsThisStep();
                    for (TWEntity e : createdObjects) {
                        if (e instanceof TWObstacle) {
                            int tx = e.getX();
                            int ty = e.getY();
                            Rectangle myZone = consensusMemory.getAssignedZone();
                            if (myZone != null && !myZone.contains(tx, ty)) continue;

                            if (!consensusMemory.isHigherPriorityAgentNear(tx, ty, 3, this.getName())) {
                                this.monitoringTarget = e;
                                System.out.println(this.getName() + " monitoring obstacle at " + tx + "," + ty);
                                break;
                            }
                        }
                    }
                }
            }
        }

        if (this.monitoringTarget != null) {
            TWEntity visibleObj = this.consensusMemory.getObservedObject(monitoringTarget.getX(), monitoringTarget.getY());
            if (visibleObj != null && visibleObj == this.monitoringTarget) {
                return executeMonitorMove();
            } else {
                this.monitoringTarget = null;
            }
        }
        return null;
    }

    protected TWThought executeExploreMove() {
        boolean stationKnown = (consensusMemory.getConsensusFuelStation().x != -1);
        boolean resetRequired = (explorationTarget == null) ||
                                (this.getX() == explorationTarget.x && this.getY() == explorationTarget.y);

        if (!stationKnown && !resetRequired) {
            if (consensusMemory.isExplored(explorationTarget.x, explorationTarget.y)) {
                resetRequired = true;
            }
        }

        if (resetRequired) {
            Rectangle zone = consensusMemory.getAssignedZone();

            if (!stationKnown) {
                explorationTarget = consensusMemory.getNearestUnexplored(this.getX(), this.getY(), true);
            } else {
                explorationTarget = null;
            }

            if (explorationTarget == null) {
                if (!stationKnown) {
                    explorationTarget = generateZonedRandomLocation(zone);
                }
                else {
                    if (this.carriedTiles.size() > 0 && !consensusMemory.hasHolesInZone(zone)) {
                        consensusMemory.refreshExplorationSubZone();
                        Rectangle subZone = consensusMemory.getExplorationSubZone();
                        explorationTarget = getRepulsiveTarget((subZone != null) ? subZone : zone);
                    } else {
                        explorationTarget = getRepulsiveTarget(zone);
                    }
                }
            }
        }
        return executePathTo(explorationTarget.x, explorationTarget.y, TWAction.MOVE);
    }

    private Int2D getRepulsiveTarget(Rectangle zone) {
        TWEnvironment env = this.getEnvironment();
        if (zone == null) return env.generateRandomLocation();

        List<Int2D> others = consensusMemory.getTeamPositions(this.getName());
        if (others.isEmpty()) {
            return generateZonedRandomLocation(zone);
        }

        Int2D bestTarget = null;
        double maxMinDistance = -1.0;

        int numCandidates = Math.max(50, (int)((zone.width * zone.height) * 0.20));

        for (int i = 0; i < numCandidates; i++) {
            Int2D cand = generateZonedRandomLocation(zone);
            double minD = Double.MAX_VALUE;
            for (Int2D other : others) {
                double d = Math.abs(cand.x - other.x) + Math.abs(cand.y - other.y);
                if (d < minD) minD = d;
            }
            if (minD > maxMinDistance) {
                maxMinDistance = minD;
                bestTarget = cand;
            }
        }
        return (bestTarget != null) ? bestTarget : generateZonedRandomLocation(zone);
    }

    private Int2D generateZonedRandomLocation(Rectangle zone) {
        int gx, gy;
        TWEnvironment env = this.getEnvironment();

        if (zone == null) return env.generateFarRandomLocation(this.getX(), this.getY(), 20);

        int padding = 3; 
        int innerX = zone.x + padding;
        int innerY = zone.y + padding;
        int innerW = zone.width - (2 * padding);
        int innerH = zone.height - (2 * padding);

        boolean usePadding = (innerW > 0 && innerH > 0);

        for (int i=0; i<50; i++) {
            if (usePadding) {
                gx = ThreadLocalRandom.current().nextInt(innerW) + innerX;
                gy = ThreadLocalRandom.current().nextInt(innerH) + innerY;
            } else {
                gx = ThreadLocalRandom.current().nextInt(zone.width) + zone.x;
                gy = ThreadLocalRandom.current().nextInt(zone.height) + zone.y;
            }

            if (env.isValidLocation(gx, gy) && !consensusMemory.isConsensusBlocked(gx, gy)) {
                return new Int2D(gx, gy);
            }
        }
        return env.generateFarRandomLocation(this.getX(), this.getY(), 10);
    }

    protected TWThought executePathTo(int tx, int ty, TWAction actionOnArrival) {
        if (this.getX() == tx && this.getY() == ty) {
            return new TWThought(actionOnArrival, TWDirection.Z);
        }
        TWPath path = this.getPathGenerator().findPath(this.getX(), this.getY(), tx, ty);

        if (path == null || path.getpath().isEmpty()) {
            explorationTarget = null;
            if (actionOnArrival == TWAction.REFUEL && this.fuelLevel <= 10) {
                return new TWThought(TWAction.MOVE, TWDirection.Z);
            }
            return executeYieldMove();
        }

        TWPathStep firstStep = path.getpath().get(0);
        TWDirection dir = firstStep.getDirection();
        if (shouldYieldMove(firstStep.getX() + dir.dx, firstStep.getY() + dir.dy, this.getEnvironment().getMessages())) {
            if (actionOnArrival == TWAction.REFUEL && this.fuelLevel <= 10) {
                return new TWThought(TWAction.MOVE, TWDirection.Z);
            }
            return executeYieldMove();
        }
        return new TWThought(TWAction.MOVE, dir);
    }

    protected TWThought executeMonitorMove() {
        if (monitoringTarget == null) return executeRandomMove();
        int targetX = monitoringTarget.getX();
        int targetY = monitoringTarget.getY();
        if (Math.abs(this.getX() - targetX) + Math.abs(this.getY() - targetY) > 2)
            return executePathTo(targetX, targetY, TWAction.MOVE);
        return executeRandomMove();
    }

    protected TWThought executeRandomMove() {
        TWDirection dir = getRandomDirection();
        int nextX = this.getX() + dir.dx;
        int nextY = this.getY() + dir.dy;
        if (shouldYieldMove(nextX, nextY, this.getEnvironment().getMessages())) {
            return executeYieldMove();
        }
        return new TWThought(TWAction.MOVE, dir);
    }

    protected TWThought executeYieldMove() {
        int superiorX = -1; int superiorY = -1;
        ArrayList<Message> messages = this.getEnvironment().getMessages();

        for (Message msg : messages) {
            String[] parts = msg.getMessage().split(":")[0].equals("STATUS") ? msg.getMessage().split(":") : null;
            if (parts != null && parts[1].compareTo(this.getName()) < 0) {
               int px = Integer.parseInt(parts[2]), py = Integer.parseInt(parts[3]);
               if (Math.abs(px - this.getX()) + Math.abs(py - this.getY()) <= 1) { superiorX=px; superiorY=py; break; }
            }
        }
        if (superiorX == -1) {
            return new TWThought(TWAction.MOVE, TWDirection.Z);
        }

        TWDirection bestDir = TWDirection.Z;
        double maxSeparation = -1.0;

        for (TWDirection dir : TWDirection.values()) {
            if (dir == TWDirection.Z) continue;
            int nextX = this.getX() + dir.dx;
            int nextY = this.getY() + dir.dy;
            if (this.getEnvironment().isCellBlocked(nextX, nextY)) continue;

            double distToSuperior = Math.abs(nextX - superiorX) + Math.abs(nextY - superiorY);
            if (distToSuperior > 1) {
                if (!shouldYieldMove(nextX, nextY, messages)) {
                    if (distToSuperior > maxSeparation) {
                        maxSeparation = distToSuperior;
                        bestDir = dir;
                    }
                }
            }
        }
        return new TWThought(TWAction.MOVE, bestDir);
    }

    protected TWDirection getRandomDirection() {
        return TWDirection.values()[this.getEnvironment().random.nextInt(4)];
    }

    protected ConsensusPathGenerator getPathGenerator() {
        int depth = Math.max(100, (this.getEnvironment().getxDimension() + this.getEnvironment().getyDimension()) * 2);
        return new ConsensusPathGenerator(this.getEnvironment(), this, depth);
    }

    protected Int2D getFuelStationFromConsensusMemory() {
        return this.consensusMemory.getConsensusFuelStation();
    }

    protected int getFuelSafetyThreshold() {
        return calculateFuelSafetyThreshold(this.getX(), this.getY());
    }

    protected int getPeerFuelSafetyThreshold(int peerX, int peerY) {
        return calculateFuelSafetyThreshold(peerX, peerY);
    }

    private int calculateFuelSafetyThreshold(int x, int y) {
        Int2D station = consensusMemory.getConsensusFuelStation();
        int defaultFuelCapacity = 500;
        int computedThreshold = 0;

        if (station.x != -1) {
            double dist = getStandardizedDistance(x, y, station.x, station.y);
            if (dist != Double.MAX_VALUE) {
                computedThreshold = (int)dist + this.fuelSafetyBuffer;
                
                int waitingPenalty = 0;
                for (Map.Entry<String, Double> peer : consensusMemory.fuelStationQueue.entrySet()) {
                    if (!peer.getKey().equals(this.getName())) {
                        if (peer.getValue() <= dist) {
                            waitingPenalty += 2;
                        }
                    }
                }
                computedThreshold += waitingPenalty;
                
            } else {
                computedThreshold = this.getEnvironment().getxDimension() + this.getEnvironment().getyDimension();
            }
        } else {
            computedThreshold = this.getEnvironment().getxDimension() + this.getEnvironment().getyDimension();
        }

        return Math.min(computedThreshold, defaultFuelCapacity - 50);
    }

    protected double getStandardizedDistance(int startX, int startY, int endX, int endY) {
        if (startX == endX && startY == endY) return 0.0;

        String cacheKey = startX + "," + startY + "," + endX + "," + endY;

        if (distanceCache.containsKey(cacheKey)) {
            return distanceCache.get(cacheKey);
        }

        TWPath path = getPathGenerator().findPath(startX, startY, endX, endY);
        double dist = (path == null) ? Double.MAX_VALUE : path.getpath().size();

        distanceCache.put(cacheKey, dist);
        return dist;
    }

    protected boolean isBestCandidate(int targetX, int targetY, int myScore, ArrayList<Message> peerMessages, TWAction action) {
        if (myScore == Integer.MIN_VALUE) return false;

        int currentStep = (int)this.getEnvironment().schedule.getSteps();
        Integer creationTime = (action == TWAction.PICKUP) ?
            consensusMemory.getTileTimestamp(targetX, targetY) :
            consensusMemory.getHoleTimestamp(targetX, targetY);

        int clusterCount = 0;
        int clusterBonusMultiplier = ((this.getEnvironment().getxDimension() + this.getEnvironment().getyDimension()) / 20) * DISTANCE_WEIGHT;
        if (action == TWAction.PICKUP) {
            clusterCount = getClusterCount(targetX, targetY, consensusMemory.getConsensusTiles().keySet());
        } else if (action == TWAction.PUTDOWN) {
            clusterCount = getClusterCount(targetX, targetY, consensusMemory.getConsensusHoles().keySet());
        }

        for (Message msg : peerMessages) {
            String content = msg.getMessage();
            String[] parts = content.split(";")[0].split(":");
            if (!parts[0].equals("STATUS")) continue; 
            String peerName = parts[1];
            if (peerName.equals(this.getName())) continue; 

            int peerX = Integer.parseInt(parts[2]);
            int peerY = Integer.parseInt(parts[3]);
            int peerInv = Integer.parseInt(parts[4]);
            double peerFuel = Double.parseDouble(parts[5]);

            if (peerFuel < getPeerFuelSafetyThreshold(peerX, peerY)) continue;

            if (action == TWAction.PICKUP && peerInv >= 3) continue;
            if (action == TWAction.PUTDOWN && peerInv == 0) continue;

            int peerZonePenalty = 0;
            Rectangle peerZone = consensusMemory.getZone(peerName);
            if (peerZone != null && !peerZone.contains(targetX, targetY)) {
                int mapDimensionSum = this.getEnvironment().getxDimension() + this.getEnvironment().getyDimension();
                peerZonePenalty = (mapDimensionSum / 2) * DISTANCE_WEIGHT; 
            }

            int peerClusterBonus = 0;
            if (action == TWAction.PICKUP) {
                peerClusterBonus = (clusterCount * clusterBonusMultiplier) + ((3 - peerInv) * 15);
            } else if (action == TWAction.PUTDOWN) {
                peerClusterBonus = (clusterCount * clusterBonusMultiplier) + (peerInv * 15);
            }

            int peerManhattan = Math.abs(peerX - targetX) + Math.abs(peerY - targetY);
            int maxPossiblePeerScore = BASE_SCORE_OFFSET - peerZonePenalty + peerClusterBonus;

            if (action == TWAction.PUTDOWN) {
                maxPossiblePeerScore += -(peerManhattan * DISTANCE_WEIGHT) + (peerInv * INVENTORY_WEIGHT);
            } else {
                maxPossiblePeerScore += -(peerManhattan * DISTANCE_WEIGHT) - (peerInv * INVENTORY_WEIGHT);
            }

            if (maxPossiblePeerScore < myScore) continue;

            double rawPeerDist = this.getStandardizedDistance(peerX, peerY, targetX, targetY);
            if (rawPeerDist == Double.MAX_VALUE) continue;
            int peerDist = (int) rawPeerDist;

            int peerSlack = Integer.MAX_VALUE;
            if (creationTime != null) {
                int life = consensusMemory.getKnownLifetime();
                int deadline = creationTime + life;
                peerSlack = deadline - (currentStep + peerDist);
                if (peerSlack < 0) continue;
            }

            int peerScore = BASE_SCORE_OFFSET - peerZonePenalty + peerClusterBonus;
            if (action == TWAction.PUTDOWN) {
                peerScore += -(peerDist * DISTANCE_WEIGHT) + (peerInv * INVENTORY_WEIGHT);
            } else {
                peerScore += -(peerDist * DISTANCE_WEIGHT) - (peerInv * INVENTORY_WEIGHT);
            }

            if (peerSlack != Integer.MAX_VALUE) {
                peerScore -= (peerSlack * URGENCY_WEIGHT);
            }

            if (peerScore > myScore) return false;
            else if (peerScore == myScore) {
                double myDist = getStandardizedDistance(this.getX(), this.getY(), targetX, targetY);
                if (rawPeerDist < myDist) return false;
                else if (rawPeerDist == myDist) {
                    if (peerName.compareTo(this.getName()) < 0) return false;
                }
            }
        }
        return true;
    }

    protected boolean shouldYieldMove(int nextX, int nextY, ArrayList<Message> peerMessages) {
        for (Message msg : peerMessages) {
            String[] parts = msg.getMessage().split(";")[0].split(":");
            if (!parts[0].equals("STATUS")) continue;

            String peerName = parts[1];
            if (peerName.equals(this.getName())) continue;

            int peerX = Integer.parseInt(parts[2]);
            int peerY = Integer.parseInt(parts[3]);

            if (peerX == nextX && peerY == nextY) return true;
            if (peerName.compareTo(this.getName()) < 0) {
                if (Math.abs(peerX - nextX) + Math.abs(peerY - nextY) <= 1) return true;
            }
        }
        return false;
    }

    @Override
    public final void communicate() {
        StringBuilder sb = new StringBuilder();
        sb.append(generateStatusString());

        Int2D station = consensusMemory.getConsensusFuelStation();
        if (station.x != -1 && this.fuelLevel < getFuelSafetyThreshold()) {
            double distToStation = getStandardizedDistance(this.getX(), this.getY(), station.x, station.y);
            if (distToStation != Double.MAX_VALUE) {
                sb.append(";FUEL_INTENT:").append(distToStation);
            }
        }

        ArrayList<TWEntity> newItems = consensusMemory.getNewDiscoveriesThisStep();
        ArrayList<TWEntity> witnessedItems = consensusMemory.getCreatedObjectsThisStep();
        int currentStep = (int) this.getEnvironment().schedule.getSteps();

        int knownLife = consensusMemory.getKnownLifetime();
        int envArea = this.getEnvironment().getxDimension() * this.getEnvironment().getyDimension();
        double dynamicPenaltyRatio = Math.min(0.40, ((double)envArea / 25000.0));
        int agePenalty = (knownLife > 0) ? (int)(knownLife * dynamicPenaltyRatio) : 0;

        for (TWEntity e : newItems) {
            int broadcastTimestamp = currentStep;
            if (!witnessedItems.contains(e)) {
                broadcastTimestamp = currentStep - agePenalty;
            }

            if (e instanceof TWTile) {
                sb.append(";TILE:").append(e.getX()).append(":").append(e.getY()).append(":").append(broadcastTimestamp);
            } else if (e instanceof TWHole) {
                sb.append(";HOLE:").append(e.getX()).append(":").append(e.getY()).append(":").append(broadcastTimestamp);
            } else if (e instanceof TWObstacle) {
                sb.append(";OBSTACLE:").append(e.getX()).append(":").append(e.getY()).append(":").append(broadcastTimestamp);
            } else if (e instanceof TWFuelStation) {
                sb.append(";FUEL_STATION:").append(e.getX()).append(":").append(e.getY());
            }
        }

        int discoveredLife = consensusMemory.getDiscoveredLifetime();
        if (discoveredLife != -1) {
            if (consensusMemory.isDiscoveredLifetimeDefinite()) {
                sb.append(";DEFINITE_LIFETIME:").append(discoveredLife);
            } else {
                sb.append(";LIFETIME:").append(discoveredLife);
            }
        }

        if (this.monitoringTarget != null) {
            sb.append(";MONITORING:").append(monitoringTarget.getX()).append(":").append(monitoringTarget.getY());
        }

        ArrayList<String> removals = consensusMemory.getRemovedObjectsBuffer();
        for (String rem : removals) {
            sb.append(";").append(rem);
        }

        Message msg = new Message(this.getName(), null, sb.toString());
        this.getEnvironment().receiveMessage(msg);

        removals.clear();
    }

    protected String generateStatusString() {
        return String.format("STATUS:%s:%d:%d:%d:%.1f", this.getName(),
            this.getX(), this.getY(), this.carriedTiles.size(), this.getFuelLevel());
    }

    @Override
    protected void act(TWThought thought) {
        lastActionFailed = false;
        try {
            switch (thought.getAction()) {
                case MOVE: this.move(thought.getDirection()); break;
                case PICKUP:
                    Object tile = this.getEnvironment().getObjectGrid().get(this.getX(), this.getY());
                    if (tile instanceof TWTile) {
                        this.pickUpTile((TWTile) tile);
                        this.consensusMemory.getConsensusTiles().remove(new Int2D(this.getX(), this.getY()));
                        this.consensusMemory.queueRemoval("TILE", this.getX(), this.getY());
                    } else {
                        this.consensusMemory.getConsensusTiles().remove(new Int2D(this.getX(), this.getY()));
                        this.consensusMemory.queueRemoval("TILE", this.getX(), this.getY());
                    }
                    break;
                case PUTDOWN:
                    Object hole = this.getEnvironment().getObjectGrid().get(this.getX(), this.getY());
                    if (hole instanceof TWHole) {
                        this.putTileInHole((TWHole) hole);
                        this.consensusMemory.getConsensusHoles().remove(new Int2D(this.getX(), this.getY()));
                        this.consensusMemory.queueRemoval("HOLE", this.getX(), this.getY());
                    } else {
                        this.consensusMemory.getConsensusHoles().remove(new Int2D(this.getX(), this.getY()));
                        this.consensusMemory.queueRemoval("HOLE", this.getX(), this.getY());
                    }
                    break;
                case REFUEL: this.refuel(); break;
            }
        } catch (CellBlockedException e) {
            lastActionFailed = true;
        } catch (Exception e) {}
    }

    protected abstract TWThought executeWorkerLogic();

    @Override
    public String getName() { return name; }
}