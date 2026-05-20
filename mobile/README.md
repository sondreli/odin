# Odin Mobile App

React Native shell for the Odin app. The UI is implemented in ClojureScript and shares re-frame state and events with the web app.

## Prerequisites

- Node and npm (from project root: `npm install --legacy-peer-deps`)
- shadow-cljs (from project root: `npx shadow-cljs`)

## Building the bundle

From the **project root** (`/Users/sondre/dev/odin`):

```bash
# One-off compile
npx shadow-cljs compile mobile

# Watch and recompile on changes
npm run watch:mobile
```

This writes the compiled JS to `mobile/out/` (entry: `out/index.js`).

## Running the app

1. **Create the React Native project** (one-time) if you don't have native folders yet:

   ```bash
   cd mobile
   npx react-native init Odin --version 0.68.7
   # If this creates a nested Odin/ folder, move its contents (android, ios, etc.) into mobile/ and remove the empty Odin folder.
   ```

   Or use an existing RN 0.68 app: replace its `index.js` with the one in this folder and ensure the app name registered in native code is `"Odin"`.

2. **iOS: install CocoaPods dependencies** (required before first run and after Podfile changes):

   ```bash
   cd mobile/Odin/ios
   pod install
   cd ../..
   ```

3. **iOS: Xcode and simulators** – You need **full Xcode** (not only “Command Line Tools”). If you see `unable to find utility "simctl"` or `xcodebuild requires Xcode`:

   - Install **Xcode** from the App Store (or ensure it’s up to date).
   - Open Xcode once and accept the license if prompted.
   - Point the active developer directory to Xcode:  
     `sudo xcode-select -s /Applications/Xcode.app/Contents/Developer`
   - Then run `pod install` in `mobile/Odin/ios` (if you haven’t yet), and run `react-native run-ios` again. Alternatively, open `mobile/Odin/ios/Odin.xcworkspace` in Xcode and run the app from there.

   If **CocoaPods** fails with an encoding error, set UTF-8 in your shell before `pod install`:  
   `export LANG=en_US.UTF-8` (or add that to `~/.zshrc` / `~/.profile`).

4. **Start the Metro bundler** from the `mobile` folder:

   ```bash
   cd mobile
   npm start
   ```

5. **Run on a device/simulator** (in another terminal):

   ```bash
   cd mobile/Odin
   npm run ios
   # or
   npm run android
   ```

The app shows a table of transactions for the current month, using the same backend API as the web app. Configure the API base URL in `src/client/api.cljs` (e.g. set `*base-url*` to your server’s URL) when not using localhost.

## Troubleshooting

- **iOS: `Invalid FBReactNativeSpec.podspec: wrong number of arguments (given 3, expected 1)`**  
  The Podfile in `mobile/Odin/ios/Podfile` sets `ENV['USE_CODEGEN_DISCOVERY'] = '1'` so the new codegen discovery path is used instead of the old `use_react_native_codegen!` call. If you recreated the iOS project, add that line at the top of the Podfile (before the `require_relative` lines), then run `pod install` again from `mobile/Odin/ios`.
