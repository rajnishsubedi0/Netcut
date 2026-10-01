# Netcut

This application, Netcut, is designed to cut off internet access for devices on a local network using the Address Resolution Protocol (ARP) spoofing technique. It requires root access to function.

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE.md)

---

## Description

Netcut allows users to identify and disconnect devices from the network. By leveraging ARP spoofing, it can effectively disrupt internet connectivity for specific devices. The app provides a user-friendly interface to manage network access, allowing users to ban, unban, protect, and save devices.

---

## Disclaimer ⚠️

> [!Warning]
> **USE AT YOUR OWN RISK.** ⛔
>
> This application is provided for **educational and authorized network administration purposes only**. By using Netcut, you acknowledge and agree that:
>
> - You are solely responsible for how you use this application.
> - You will only use it on networks you **own** or have **explicit written permission** to test.
> - ARP spoofing may be **illegal** in your jurisdiction when used without authorization.
> - The author(s) and contributor(s) of Netcut are **not responsible** for any data loss, network disruption, legal consequences, or damage caused by this app.
> - This software is provided **"AS IS"**, without warranty of any kind.
>
> If you do not agree with these terms, **do not use this application**.

---

## Installation 🛠️

**Prerequisites:**

*   **Rooted Android Device**: This application requires root access to function correctly. Ensure your device is rooted with Magisk, SuperSU, or a compatible root solution.

**Steps:**

You can download the app from https://github.com/rajnishsubedi0/Netcut/releases/ or build on your own using following.

1.  **Clone the Repository**:
    ```bash
    git clone https://github.com/rajnishsubedi0/Netcut.git
    cd Netcut
    ```

2.  **Build the Android Application**:
    This project uses Gradle for building. You can build the project using Android Studio or the Gradle wrapper.

    *   **Using Gradle Wrapper**:
        ```bash
        ./gradlew assembleDebug
        ```
        (Replace `assembleDebug` with `assembleRelease` for a release build).

3.  **Install the APK**:
    Locate the generated APK file (usually in `app/build/outputs/apk/`) and install it on your rooted Android device. You may need to enable installation from unknown sources in your device settings.

4.  **Notification Permissions**:
    To monitor and enforce services from notification, it will request notification permission.

5.  **Grant Root Permissions**:
    When you first launch Netcut, it will prompt for root access. Grant the permission when prompted.

6.  **Disable Battery Optimization**:
    For continuous operation, it's highly recommended to disable battery optimization for Netcut. The app will prompt you to do this on first launch.

---

## Usage 🧑‍💻

Netcut operates as a background service that requires root permissions.

1.  **Start the Service**: Launch the Netcut application. Tap the 'Start' button (Play icon) to begin the service. The service will begin scanning your network.
2.  **View Devices**: The main screen displays connected devices. You can switch between tabs for 'Connected', 'Banned', 'Protected', and 'Saved' devices.
3.  **Ban/Unban Devices**:
    *   From the 'Connected' tab, you can press ban/unban button or long-press a device to select it, then use the 'Ban' or 'Unban' buttons.
    *   Alternatively, tap on a device to view its details and use the 'Ban Device' or 'Unban Device' buttons within the details screen.
    *   You can also use the 'Ban All' and 'Restore All' buttons at the bottom of the screen on the main screen to ban all unprotected online devices and restore them.
4.  **Protect Devices**: In the device details screen, you can choose to 'Protect' a device. Protected devices cannot be banned by Netcut.
5.  **Save Devices**: You can save specific devices from the device details screen. Saved devices appear in the 'Saved' tab.
6.  **Network Scanning**: The app automatically scans your network. You can manually trigger a scan by pulling down to refresh on the 'Connected' tab.
7.  **Settings**: Access the Settings screen to configure scan intervals, enable/disable unknown device alerts, and manage auto-ban preferences.

---

## Quick Start 🚀

1.  **Ensure Root Access**: Verify that your device is properly rooted.
2.  **Launch Netcut**: Open the Netcut app.
3.  **Start Service**: Tap the play button to start the Netcut service. You will likely be prompted to grant root permissions.
4.  **Monitor Network**: Observe the list of connected devices. New devices appearing may indicate unauthorized access.
5.  **Take Action**: Select devices to ban or protect as needed using the options provided.
6.  **Check Logs**: Review the session logs for a history of actions and detected events.

---

## Contributing 🤝

Contributions are welcome! Feel free to fork the repository, open issues, or submit pull requests.

---

## License 📄

This project is licensed under the MIT License — see the [LICENSE.md](LICENSE.md) file for details.
