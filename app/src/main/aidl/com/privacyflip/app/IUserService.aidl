package com.privacyflip.app;

// Runs inside the Shizuku shell process. Only fixed, built-in tasks exist here: no method accepts free text.
interface IUserService {
    // Destroy method required by Shizuku.
    void destroy() = 16777114;

    // Switches one allow-listed feature. Returns "EXIT:<code>\n<output>".
    String apply(String featureKey, boolean on) = 1;

    // Lets Privacy Flip keep running in the background (app ops, battery whitelist, standby bucket).
    String keepAlive() = 2;

    // Turns on Privacy Flip's own accessibility service without opening Settings.
    String enableAccessibility() = 3;

    // Sets the screen brightness mode (auto or manual) and level. Returns "EXIT:<code>\n<output>".
    String setBrightness(int value, boolean auto) = 4;
}
