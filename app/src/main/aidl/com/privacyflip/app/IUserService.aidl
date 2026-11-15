package com.privacyflip.app;

// Runs inside the Shizuku shell process. Only allow-listed feature keys are accepted, never free text.
interface IUserService {
    // Destroy method required by Shizuku.
    void destroy() = 16777114;

    // Returns "EXIT:<code>\n<output>".
    String apply(String featureKey, boolean on) = 1;
}
