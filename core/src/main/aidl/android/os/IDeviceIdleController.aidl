package android.os;

interface IDeviceIdleController {
    void addPowerSaveWhitelistApp(String packageName);
    void removePowerSaveWhitelistApp(String packageName);
    String[] getSystemPowerWhitelistExceptIdle();
    String[] getSystemPowerWhitelist();
    String[] getUserPowerWhitelist();
    String[] getFullPowerWhitelistExceptIdle();
    String[] getFullPowerWhitelist();
    int[] getAppIdWhitelistExceptIdle();
    int[] getAppIdWhitelist();
    int[] getAppIdUserWhitelist();
    int[] getAppIdTempWhitelist();
    boolean isPowerSaveWhitelistExceptIdleApp(String packageName);
    boolean isPowerSaveWhitelistApp(String packageName);
}
