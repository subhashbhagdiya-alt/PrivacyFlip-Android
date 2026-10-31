package com.privacyflip.app;

interface IPrivacyFlipShell {
    String setOption(String option, boolean enabled);
    String getOptionState(String option);
}
