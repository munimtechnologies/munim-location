/**
 * @format
 */

import { AppRegistry, NativeModules } from 'react-native';
import { registerBackgroundHandler } from 'munim-location';
import App from './App';
import { name as appName } from './app.json';
import { log } from './src/eventLog';

// Registered at the top level so Android Headless JS runs and iOS
// background relaunches (which may start JS without any UI) reach it.
registerBackgroundHandler(async event => {
  NativeModules.ChecksReport?.logBackground?.(
    JSON.stringify({
      name: event.name,
      headless: event.headless,
      replayed: event.replayed,
      payload: event.payload,
    }),
  );
  log(
    `background:${event.name}${event.headless ? ' (headless)' : ''}${
      event.replayed ? ' (replayed)' : ''
    }`,
    event.payload,
  );
});

AppRegistry.registerComponent(appName, () => App);
