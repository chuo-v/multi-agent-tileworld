package tileworld.agent;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import sim.engine.Schedule;
import sim.util.Bag;
import sim.util.Int2D;
import sim.util.IntBag;
import tileworld.Parameters;
import tileworld.environment.TWEntity;
import tileworld.environment.TWFuelStation;
import tileworld.environment.TWHole;
import tileworld.environment.TWObject;
import tileworld.environment.TWObstacle;
import tileworld.environment.TWTile;

public class ConsensusMemory extends TWAgentWorkingMemory {

    private final TWAgent me;
    private final Schedule schedule;

    private boolean fuelStationDiscoveredLog = false;

    private TWAgentPercept[][] privateShadow;
    private double[][] lastObservationGrid;
    private boolean[][] witnessedCreationGrid;
    private boolean[][] exploredGrid;
    private int exploredCellCount = 0;

    private Rectangle assignedZone = null;
    private Rectangle explorationSubZone = null;
    private Map<String, Rectangle> allAgentZones = new HashMap<>();
    private Map<String, Int2D> teamPositions = new HashMap<>();
    private boolean zoneAssignmentIsPhase2 = false;
    private int phase2ZoneOverlap;

    private ArrayList<TWAgent> nearbyAgents;
    private ArrayList<TWEntity> newDiscoveriesThisStep;
    private ArrayList<TWEntity> createdObjectsThisStep;
    private ArrayList<String> removedObjectsBuffer;

    private int discoveredLifetime = -1;
    private boolean discoveredLifetimeIsDefinite = false;

    private HashMap<Int2D, Integer> consensusTiles;
    private HashMap<Int2D, Integer> consensusHoles;
    private HashMap<Int2D, Integer> consensusObstacles;
    private HashMap<Int2D, ArrayList<String>> monitorClaims;
    public HashMap<String, Double> fuelStationQueue;

    private Int2D consensusFuelStation;
    private int objectLifetime;
    private boolean definiteLifetimeKnown = false;

    public ConsensusMemory(TWAgent moi, Schedule schedule, int x, int y) {
        super(moi, schedule, x, y);
        this.me = moi;
        this.schedule = schedule;

        this.privateShadow = new TWAgentPercept[x][y]; 
        this.lastObservationGrid = new double[x][y];
        this.witnessedCreationGrid = new boolean[x][y];
        this.exploredGrid = new boolean[x][y];

        for(int i=0; i<x; i++) {
            for(int j=0; j<y; j++) {
                lastObservationGrid[i][j] = -1;
                exploredGrid[i][j] = false;
            }
        }

        this.nearbyAgents = new ArrayList<>();
        this.newDiscoveriesThisStep = new ArrayList<>();
        this.createdObjectsThisStep = new ArrayList<>();
        this.removedObjectsBuffer = new ArrayList<>();

        this.consensusTiles = new HashMap<>();
        this.consensusHoles = new HashMap<>();
        this.consensusObstacles = new HashMap<>();
        this.monitorClaims = new HashMap<>();
        this.fuelStationQueue = new HashMap<>();

        this.phase2ZoneOverlap = (int)(me.getEnvironment().getyDimension() * 0.25);

        this.consensusFuelStation = new Int2D(-1, -1);
        this.objectLifetime = 20; 
    }

    public void performZoneAssignmentIfNecessary() {
        if (schedule.getSteps() < 2) return;

        teamPositions.put(me.getName(), new Int2D(me.getX(), me.getY()));

        boolean stationFound = (this.consensusFuelStation.x != -1);

        if (assignedZone != null && (!stationFound || zoneAssignmentIsPhase2)) {
            return;
        }

        allAgentZones.clear(); 

        int W = me.getEnvironment().getxDimension();
        int H = me.getEnvironment().getyDimension();
        int midH = H / 2;

        List<Map.Entry<String, Int2D>> sortedAgents = new ArrayList<>(teamPositions.entrySet());
        sortedAgents.sort((a, b) -> {
            int cmp = Integer.compare(a.getValue().y, b.getValue().y);
            if (cmp != 0) return cmp;
            return a.getKey().compareTo(b.getKey());
        });

        int teamSize = sortedAgents.size();
        int northCount = (int) Math.ceil(teamSize / 2.0);

        List<Map.Entry<String, Int2D>> northGroup = new ArrayList<>();
        List<Map.Entry<String, Int2D>> southGroup = new ArrayList<>();

        for (int i = 0; i < teamSize; i++) {
            if (i < northCount) northGroup.add(sortedAgents.get(i));
            else southGroup.add(sortedAgents.get(i));
        }

        if (stationFound) {
            if (!zoneAssignmentIsPhase2) {
                System.out.println("PHASE 2 SWITCH " + me.getName() + " calculating global zones.");
                this.zoneAssignmentIsPhase2 = true;
            }

            int halfOverlap = this.phase2ZoneOverlap / 2;
            Rectangle northZone = new Rectangle(0, 0, W, midH + halfOverlap);
            Rectangle southZone = new Rectangle(0, midH - halfOverlap, W, (H - midH) + halfOverlap);

            for (Map.Entry<String, Int2D> entry : northGroup) allAgentZones.put(entry.getKey(), northZone);
            for (Map.Entry<String, Int2D> entry : southGroup) allAgentZones.put(entry.getKey(), southZone);

        } else {
            assignColumns(northGroup, 0, midH, W, 0);
            assignColumns(southGroup, midH, H - midH, W, 0);

            this.zoneAssignmentIsPhase2 = false;
            this.explorationSubZone = allAgentZones.get(me.getName());

            System.out.println("ZONING " + me.getName() + " assigned Phase 1 Zone Strict " + this.explorationSubZone);
        }

        this.assignedZone = allAgentZones.get(me.getName());
    }

    public void refreshExplorationSubZone() {
        if (!zoneAssignmentIsPhase2 || assignedZone == null) return;

        int W = me.getEnvironment().getxDimension();
        List<Map.Entry<String, Int2D>> myRowPeers = new ArrayList<>();

        for (Map.Entry<String, Int2D> entry : teamPositions.entrySet()) {
            Rectangle pZone = allAgentZones.get(entry.getKey());
            if (pZone != null && pZone.equals(this.assignedZone)) {
                myRowPeers.add(entry);
            }
        }

        if (myRowPeers.isEmpty()) return;

        myRowPeers.sort((a, b) -> {
            int cmp = Integer.compare(a.getValue().x, b.getValue().x);
            if (cmp != 0) return cmp;
            return a.getKey().compareTo(b.getKey());
        });

        int myIndex = -1;
        for(int i=0; i<myRowPeers.size(); i++) {
            if (myRowPeers.get(i).getKey().equals(me.getName())) {
                myIndex = i;
                break;
            }
        }

        if (myIndex != -1) {
            int count = myRowPeers.size();
            int subZoneWidth = W / count;
            int halfOverlap = this.phase2ZoneOverlap / 2;

            int rawStart = myIndex * subZoneWidth;
            int rawEnd = (myIndex == count - 1) ? W : (myIndex + 1) * subZoneWidth;

            int effectiveStart = Math.max(0, rawStart - halfOverlap);
            if (myIndex == 0) effectiveStart = 0;

            int effectiveEnd = Math.min(W, rawEnd + halfOverlap);
            if (myIndex == count - 1) effectiveEnd = W;

            int width = effectiveEnd - effectiveStart;

            this.explorationSubZone = new Rectangle(effectiveStart, this.assignedZone.y, width, this.assignedZone.height);
        }
    }

    private void assignColumns(List<Map.Entry<String, Int2D>> group, int yStart, int height, int totalWidth, int overlap) {
        group.sort((a, b) -> {
            int cmp = Integer.compare(a.getValue().x, b.getValue().x);
            if (cmp != 0) return cmp;
            return a.getKey().compareTo(b.getKey());
        });

        int count = group.size();
        if (count == 0) return;

        int subZoneWidth = totalWidth / count;
        int halfOverlap = overlap / 2;

        for (int i = 0; i < count; i++) {
            int rawStart = i * subZoneWidth;
            int rawEnd = (i == count - 1) ? totalWidth : (i + 1) * subZoneWidth;

            int effectiveStart = Math.max(0, rawStart - halfOverlap);
            if (i == 0) effectiveStart = 0;

            int effectiveEnd = Math.min(totalWidth, rawEnd + halfOverlap);
            if (i == count - 1) effectiveEnd = totalWidth;

            int width = effectiveEnd - effectiveStart;

            Rectangle z = new Rectangle(effectiveStart, yStart, width, height);
            allAgentZones.put(group.get(i).getKey(), z);
        }
    }

    private void markAreaExplored(int cx, int cy, int range) {
        int xDim = me.getEnvironment().getxDimension();
        int yDim = me.getEnvironment().getyDimension();

        int startX = Math.max(0, cx - range);
        int endX = Math.min(xDim - 1, cx + range);
        int startY = Math.max(0, cy - range);
        int endY = Math.min(yDim - 1, cy + range);

        for (int i = startX; i <= endX; i++) {
            for (int j = startY; j <= endY; j++) {
                if (!exploredGrid[i][j]) {
                    exploredGrid[i][j] = true;
                    exploredCellCount++;
                }
            }
        }
    }

    public Int2D getNearestUnexplored(int startX, int startY, boolean restrictToZone) {
        if (isMapFullyExplored()) return null;

        Int2D target = searchUnexploredBFS(startX, startY, restrictToZone, true);
        if (target != null) return target;

        return searchUnexploredBFS(startX, startY, restrictToZone, false);
    }

    private Int2D searchUnexploredBFS(int startX, int startY, boolean restrictToZone, boolean usePadding) {
        int xDim = me.getEnvironment().getxDimension();
        int yDim = me.getEnvironment().getyDimension();

        boolean[][] visitedBFS = new boolean[xDim][yDim];
        Queue<Int2D> queue = new LinkedList<>();

        Int2D startNode = new Int2D(startX, startY);
        queue.add(startNode);
        visitedBFS[startX][startY] = true;

        Rectangle searchZone = assignedZone;
        if (restrictToZone && assignedZone != null && usePadding) {
            int pad = 3; 
            int ix = assignedZone.x + pad;
            int iy = assignedZone.y + pad;
            int iw = assignedZone.width - (2 * pad);
            int ih = assignedZone.height - (2 * pad);

            if (iw > 0 && ih > 0) {
                searchZone = new Rectangle(ix, iy, iw, ih);
            }
        }

        while (!queue.isEmpty()) {
            Int2D current = queue.poll();

            if (!exploredGrid[current.x][current.y]) {
                if (restrictToZone && searchZone != null) {
                    if (searchZone.contains(current.x, current.y)) return current;
                } else {
                    return current;
                }
            }

            int[] dx = {0, 0, 1, -1};
            int[] dy = {1, -1, 0, 0};

            for (int i = 0; i < 4; i++) {
                int nx = current.x + dx[i];
                int ny = current.y + dy[i];

                if (nx >= 0 && nx < xDim && ny >= 0 && ny < yDim && !visitedBFS[nx][ny]) {
                    if (!isConsensusBlocked(nx, ny)) {
                        visitedBFS[nx][ny] = true;
                        queue.add(new Int2D(nx, ny));
                    }
                }
            }
        }
        return null;
    }

    private boolean isMapFullyExplored() {
        int totalCells = me.getEnvironment().getxDimension() * me.getEnvironment().getyDimension();
        return exploredCellCount >= totalCells;
    }

    public boolean isExplored(int x, int y) {
        if (x < 0 || x >= me.getEnvironment().getxDimension() || y < 0 || y >= me.getEnvironment().getyDimension()) return true;
        return exploredGrid[x][y];
    }

    @Override
    public void updateMemory(Bag sensedObjects, IntBag objectXCoords, IntBag objectYCoords, Bag sensedAgents, IntBag agentXCoords, IntBag agentYCoords) {

        super.updateMemory(sensedObjects, objectXCoords, objectYCoords, sensedAgents, agentXCoords, agentYCoords);

        newDiscoveriesThisStep.clear();
        createdObjectsThisStep.clear();
        nearbyAgents.clear();
        discoveredLifetime = -1;
        discoveredLifetimeIsDefinite = false;

        double currentTime = this.getSimulationTime();
        int range = Parameters.defaultSensorRange;
        int xDim = me.getEnvironment().getxDimension();
        int yDim = me.getEnvironment().getyDimension();

        markAreaExplored(me.getX(), me.getY(), range);

        for(int i=0; i<sensedAgents.size(); i++) {
            Object obj = sensedAgents.get(i);
            if(obj instanceof TWAgent && obj != me) nearbyAgents.add((TWAgent)obj);
        }

        for (int x = me.getX() - range; x <= me.getX() + range; x++) {
            for (int y = me.getY() - range; y <= me.getY() + range; y++) {
                if (x < 0 || x >= xDim || y < 0 || y >= yDim) continue;

                TWAgentPercept prevPercept = privateShadow[x][y];
                if (prevPercept != null) {
                    TWEntity oldObj = prevPercept.getO();

                    if (!sensedObjects.contains(oldObj)) {
                        if (oldObj instanceof TWObject) {
                            double lastSeen = lastObservationGrid[x][y];

                            if ((currentTime - lastSeen) <= 1.5) {
                                double startTime = prevPercept.getT();
                                int calculatedLife = (int) (currentTime - startTime);

                                if (witnessedCreationGrid[x][y] && (oldObj instanceof TWObstacle)) {
                                    System.out.println("Passive Crowdsourcing " + me.getName() + " found DEFINITE lifetime " + calculatedLife);
                                    this.updateLifetime(calculatedLife);
                                    this.discoveredLifetime = calculatedLife;
                                    this.definiteLifetimeKnown = true;
                                    this.discoveredLifetimeIsDefinite = true;
                                } else {
                                    if (calculatedLife > this.objectLifetime) {
                                        this.updateLifetime(calculatedLife);
                                        this.discoveredLifetime = calculatedLife;
                                        System.out.println("Passive Crowdsourcing " + me.getName() + " estimated lifetime >= " + calculatedLife);
                                    }
                                }
                            }
                        }
                        privateShadow[x][y] = null;
                        witnessedCreationGrid[x][y] = false;
                    }
                }
            }
        }

        for (int i = 0; i < sensedObjects.size(); i++) {
            TWEntity o = (TWEntity) sensedObjects.get(i);
            if (!(o instanceof TWObject) && !(o instanceof TWFuelStation)) continue;

            int x = o.getX();
            int y = o.getY();
            Int2D pos = new Int2D(x, y);

            boolean isKnownInConsensus = false;
            if (o instanceof TWTile) isKnownInConsensus = consensusTiles.containsKey(pos);
            else if (o instanceof TWHole) isKnownInConsensus = consensusHoles.containsKey(pos);
            else if (o instanceof TWObstacle) isKnownInConsensus = consensusObstacles.containsKey(pos);
            else if (o instanceof TWFuelStation) isKnownInConsensus = (consensusFuelStation.x != -1);

            if (!isKnownInConsensus && !newDiscoveriesThisStep.contains(o)) {
                newDiscoveriesThisStep.add(o);
            }

            TWAgentPercept existing = privateShadow[x][y];
            if (existing != null && existing.getO() == o) {
                // Keep original
            } else {
                boolean witnessCreation = false;
                double lastLook = lastObservationGrid[x][y];
                if (lastLook != -1 && (currentTime - lastLook) <= 1.5) {
                    witnessCreation = true;
                }

                privateShadow[x][y] = new TWAgentPercept(o, currentTime);
                witnessedCreationGrid[x][y] = witnessCreation;

                if (witnessCreation && o instanceof TWObject) {
                    createdObjectsThisStep.add(o);
                }
            }
        }

        for (int x = me.getX() - range; x <= me.getX() + range; x++) {
            for (int y = me.getY() - range; y <= me.getY() + range; y++) {
                if (x < 0 || x >= xDim || y < 0 || y >= yDim) continue;
                lastObservationGrid[x][y] = currentTime;
            }
        }
    }

    public void updateState(ArrayList<Message> messages, int currentStep) {
        monitorClaims.clear();
        fuelStationQueue.clear();

        for (Message msg : messages) {
            String content = msg.getMessage();
            String[] parts = content.split(";");
            String senderName = null;
            if (parts.length > 0 && parts[0].startsWith("STATUS")) {
                senderName = parts[0].split(":")[1];

                try {
                    int px = Integer.parseInt(parts[0].split(":")[2]);
                    int py = Integer.parseInt(parts[0].split(":")[3]);
                    teamPositions.put(senderName, new Int2D(px, py));
                    markAreaExplored(px, py, Parameters.defaultSensorRange);
                } catch (Exception e) {}
            }

            for (String part : parts) {
                String[] data = part.trim().split(":");
                String type = data[0];
                try {
                    if (type.equals("TILE")) {
                        Int2D pos = new Int2D(Integer.parseInt(data[1]), Integer.parseInt(data[2]));
                        consensusTiles.put(pos, Integer.parseInt(data[3]));
                    } else if (type.equals("HOLE")) {
                        Int2D pos = new Int2D(Integer.parseInt(data[1]), Integer.parseInt(data[2]));
                        consensusHoles.put(pos, Integer.parseInt(data[3]));
                    } else if (type.equals("OBSTACLE")) {
                        consensusObstacles.put(new Int2D(Integer.parseInt(data[1]), Integer.parseInt(data[2])), Integer.parseInt(data[3]));
                    } else if (type.equals("FUEL_STATION")) {
                        int x = Integer.parseInt(data[1]);
                        int y = Integer.parseInt(data[2]);
                        if (x != -1 && y != -1 && this.consensusFuelStation.x == -1) {
                             if (!fuelStationDiscoveredLog) {
                                System.out.println("BENCHMARK Fuel Station FOUND at Step " + currentStep + " by " + me.getName() + " Location " + x + " " + y);
                                fuelStationDiscoveredLog = true;
                            }
                            this.consensusFuelStation = new Int2D(x, y);
                        }
                    } else if (type.equals("LIFETIME")) {
                        updateLifetime(Integer.parseInt(data[1]));
                    } else if (type.equals("DEFINITE_LIFETIME")) {
                        int val = Integer.parseInt(data[1]);
                        updateLifetime(val);
                        this.definiteLifetimeKnown = true;
                    } else if (type.equals("FUEL_INTENT") && senderName != null) {
                        double distToStation = Double.parseDouble(data[1]);
                        fuelStationQueue.put(senderName, distToStation);
                    } else if (type.equals("MONITORING") && senderName != null) {
                        int x = Integer.parseInt(data[1]);
                        int y = Integer.parseInt(data[2]);
                        Int2D pos = new Int2D(x, y);
                        monitorClaims.putIfAbsent(pos, new ArrayList<>());
                        monitorClaims.get(pos).add(senderName);
                    } else if (type.equals("REMOVE")) {
                        String objType = data[1];
                        int x = Integer.parseInt(data[2]);
                        int y = Integer.parseInt(data[3]);
                        Int2D pos = new Int2D(x, y);

                        if (objType.equals("TILE")) consensusTiles.remove(pos);
                        else if (objType.equals("HOLE")) consensusHoles.remove(pos);
                    }
                } catch (Exception e) {}
            }
        }
        
        purgeExpiredConsensusObjects(currentStep);
    }

    private void purgeExpiredConsensusObjects(int currentStep) {
        int threshold = currentStep - this.objectLifetime;
        consensusTiles.entrySet().removeIf(entry -> entry.getValue() <= threshold);
        consensusHoles.entrySet().removeIf(entry -> entry.getValue() <= threshold);
        consensusObstacles.entrySet().removeIf(entry -> entry.getValue() <= threshold);
    }

    public boolean isHigherPriorityAgentNear(int tx, int ty, int sensorRange, String myName) {
        for (TWAgent other : nearbyAgents) {
            double distToTarget = Math.abs(other.getX() - tx) + Math.abs(other.getY() - ty);
            if (distToTarget <= sensorRange) {
                if (other.getName().compareTo(myName) < 0) return true;
            }
        }
        return false;
    }

    public boolean hasHigherPriorityMonitor(int x, int y, String myName) {
        Int2D pos = new Int2D(x, y);
        if (!monitorClaims.containsKey(pos)) return false;
        for (String otherAgent : monitorClaims.get(pos)) {
            if (otherAgent.compareTo(myName) < 0) return true;
        }
        return false;
    }

    public boolean isAnyAgentMonitoring(String myName) {
        for(ArrayList<String> agents : monitorClaims.values()) {
            for(String agent : agents) {
                if(!agent.equals(myName)) return true;
            }
        }
        return false;
    }

    public boolean hasGlobalHigherPriorityMonitor(String myName) {
         for(ArrayList<String> agents : monitorClaims.values()) {
            for(String agent : agents) {
                if(agent.compareTo(myName) < 0) return true;
            }
        }
        return false;
    }

    public void queueRemoval(String type, int x, int y) {
        removedObjectsBuffer.add("REMOVE:" + type + ":" + x + ":" + y);
    }

    public Rectangle getZone(String agentName) { return allAgentZones.get(agentName); }
    public Rectangle getAssignedZone() { return assignedZone; }
    public Rectangle getExplorationSubZone() { return this.explorationSubZone; }

    public List<Int2D> getTeamPositions(String excludeName) {
        List<Int2D> positions = new ArrayList<>();
        for (Map.Entry<String, Int2D> entry : teamPositions.entrySet()) {
            if (!entry.getKey().equals(excludeName)) {
                positions.add(entry.getValue());
            }
        }
        return positions;
    }

    public boolean hasHolesInZone(Rectangle zone) {
        if (zone == null) return false;
        if (consensusHoles.isEmpty()) return false;
        for (Int2D hole : consensusHoles.keySet()) {
            if (zone.contains(hole.x, hole.y)) return true;
        }
        return false;
    }

    public ArrayList<String> getRemovedObjectsBuffer() { return removedObjectsBuffer; }

    public Integer getTileTimestamp(int x, int y) { return consensusTiles.get(new Int2D(x, y)); }
    public Integer getHoleTimestamp(int x, int y) { return consensusHoles.get(new Int2D(x, y)); }

    public TWEntity getObservedObject(int x, int y) {
        if (x < 0 || x >= me.getEnvironment().getxDimension()) return null;
        if (y < 0 || y >= me.getEnvironment().getyDimension()) return null;
        TWAgentPercept p = privateShadow[x][y];
        return (p != null) ? p.getO() : null;
    }

    public ArrayList<TWEntity> getCreatedObjectsThisStep() { return createdObjectsThisStep; }
    public boolean isConsensusBlocked(int x, int y) { return consensusObstacles.containsKey(new Int2D(x, y)); }
    public ArrayList<TWEntity> getNewDiscoveriesThisStep() { return newDiscoveriesThisStep; }
    public int getDiscoveredLifetime() { return discoveredLifetime; }
    public boolean isDiscoveredLifetimeDefinite() { return discoveredLifetimeIsDefinite; }
    public boolean isDefiniteLifetimeKnown() { return definiteLifetimeKnown; }
    public int getKnownLifetime() { return objectLifetime; }
    public void updateLifetime(int val) { this.objectLifetime = Math.max(this.objectLifetime, val); }
    public Int2D getConsensusFuelStation() { return consensusFuelStation; }
    public HashMap<Int2D, Integer> getConsensusTiles() { return consensusTiles; }
    public HashMap<Int2D, Integer> getConsensusHoles() { return consensusHoles; }
    private double getSimulationTime() { return me.getEnvironment().schedule.getTime(); }
}