import type { CapacitorConfig } from '@capacitor/cli';

const config: CapacitorConfig = {
  appId: 'tf.monochrome.app',
  appName: 'Monochrome',
  webDir: 'www',
  server: {
    url: 'https://monochrome.tf',
    allowNavigation: ['*.monochrome.tf'],
  },
};

export default config;
