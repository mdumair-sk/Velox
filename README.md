# Velox

Velox is an Android application that provides a hands-free voice assistant experience. It runs as a background service, allowing you to use voice commands to control your device.

## Features

*   **Voice-activated commands:** Control your device using your voice.
*   **Shake to activate:** Shake your device to start the voice assistant.
*   **Incoming call handling:** Velox can announce callers and provides options to manage incoming calls.
*   **Text-to-Speech:** Get auditory feedback and information.
*   **Background service:** Velox is always ready, running seamlessly in the background as a foreground service.

## Getting Started

To get a local copy up and running, follow these simple steps.

### Prerequisites

*   Android Studio Dolphin | 2021.3.1 or later
*   Android SDK 34
*   Gradle 8.0

### Installation

1.  Clone the repo
    ```sh
    git clone https://github.com/your_username/velox.git
    ```
2.  Open the project in Android Studio.
3.  Let Android Studio download the dependencies and sync the project.
4.  Run the app on an Android device or emulator.

## Usage

Once the app is installed, the `CoreService` will run in the background.

*   **Activate the assistant:** Shake your device to trigger the voice command flow. You will hear a short beep to indicate it's listening.
*   **Speak your command:** After the beep, you can speak your command.

## Contributing

Contributions are what make the open-source community such an amazing place to learn, inspire, and create. Any contributions you make are **greatly appreciated**.

If you have a suggestion that would make this better, please fork the repo and create a pull request. You can also simply open an issue with the tag "enhancement".
Don't forget to give the project a star! Thanks again!

1.  Fork the Project
2.  Create your Feature Branch (`git checkout -b feature/AmazingFeature`)
3.  Commit your Changes (`git commit -m '''Add some AmazingFeature'''`)
4.  Push to the Branch (`git push origin feature/AmazingFeature`)
5.  Open a Pull Request

## License

Distributed under the MIT License. See `LICENSE.txt` for more information.
