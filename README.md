# The Neurolyzer Streamer

It is application, which parses input of EEG devices and sends data to
the Neurolyzer service. Currently, only NeuroSky headsets are supported.

## Installation

1. Install Java version 7+ on your system. If you are on Mac OS X, it must be
available from scratch. Linux users can to search for Java using standard
package manager for their distribution. Windows users can consult
[official page](https://www.java.com/en/download/help/download_options.xml#windows)
2. Download the latest [release](https://github.com/efficiosro/nl-streamer/raw/master/releases/nl-streamer-0.2.0.zip) and unpack it

## Linux Instructions

This example pressumes, that Linux distribution is Ubuntu and EEG headset
is NeuroSky MindWave Mobile.

First of all, go to bluetooth configuration in System Settings, enable it,
and pair your headset (PIN is 0000). After pairing, click on
"MindWave Mobile" in "Devices" section. At the right panel you will see
word "Address" following by XX:XX:XX:XX:XX:XX (actual device MAC address,
where X could be digit or letter from A to F). Copy MAC address.

Install `rfcomm` utility:

    $ sudo apt-get install rfcomm

Edit file `/etc/bluetooth/rfcomm.conf`:

    $ sudo nano /etc/bluetooth/rfcomm.conf

It should looks like that:

```
rfcomm0 {
        bind yes;
        device XX:XX:XX:XX:XX:XX;
        channel 1;
        comment "Bluetooth device";
}
```

Where `device XX:XX:XX:XX:XX:XX;` must contain actual MAC address from bluetooth's
system settings page.

Save file (press Control-O, then Enter) and exit (press Control-X).

Restart bluetooth service:

    $ sudo service bluetooth restart

Now, follow the Usage instructions.

## Usage

`nl-streamer` is terminal application for now, there is special helper shell
script, which ease usage on *nix platforms, like Linux or Mac OS X.

Command, which runs application, must look like that:

    $ ./nl-streamer [command] [options]

NOTE: `$` sign is just command shell prompt and it can be different in your terminal.

Open directory with unpacked application in terminal and run the next command:

    $ ./nl-streamer --help

#### Commands

* `ui` - run application with user interface
* `ports` - print list of available ports to connect and exit
* `neurosky` - connect and process data from NeuroSky headsets

#### Options

* `-p, --port ID` - ID of the EEG headset port to read from
* `-c, --config FILENAME` - set configuration file (JSON), defaults to
`config.json` in current directory
* `-h, --help` - displays help message

## Configuration File

To basic `nl-streamer` configuration you will need to setup configuration file.
Example file could be found in the application directory, it called
`config.example.json`. Open the file and put right configuration values into it.
Then rename `config.example.json` to `config.json`, or, alternatively, you can use
`-c config.example.json` option, when launch the app.

Configuration options:


* `"protocol"` - communication protocol. String.
* `"host"` - the Neurolyzer API hostname or address. String.
* `"profile-id"` - ID of profile you want to post data to. Integer.
* `"exercise-id"` - ID of exercise you want to post data to. Integer.
* `"token"` - token to access the Neurolyzer API. String.

## Examples

Start the streamer, which reads data from NeuroSky headset on port 4 and send
it to the Neurolyzer service.

    $ nl-streamer neurosky -p 4

## Contribute

* Fork `nl-streamer` repository
* Make changes and test them
* Make pull request to this repository

## Thanks

Sam Aaron and Jeff Rose, contributors of `serial-port`, for the library,
which is copied over to this repository.

## License

Copyright © 2015 Efficio s.r.o.

Distributed under the Eclipse Public License.
