package tileworld;

/**
 * InternalParameters1
 *
 * Description:
 *
 * Class used to store global simulation parameters.
 * Environment related parameters are still in the TWEnvironment class.
 *
 * Note:
 *
 * Unlike Parameters.java and Parameters2.java, this parameter configuration was
 * not originally provided, and was just added for internal use to test different
 * parameter configurations.
 *
 */
public class InternalParameters1 {

    // Simulation Parameters
    public final static int seed = 4162012; //no effect with gui
    public static final long endTime = 5000; //no effect with gui

    // Agent Parameters
    public static final int defaultFuelLevel = 500;
    public static final int defaultSensorRange = 3;

    // Environment Parameters
    public static final int xDimension = 20; //size in cells
    public static final int yDimension = 20;

    // Object Parameters
    // mean, dev: control the number of objects to be created in every time step (i.e. average object creation rate)
    public static final double tileMean = 0.03;
    public static final double holeMean = 0.03;
    public static final double obstacleMean = 0.03;
    public static final double tileDev = 0.05f;
    public static final double holeDev = 0.05f;
    public static final double obstacleDev = 0.05f;
    // the life time of each object
    public static final int lifeTime = 40;

}
