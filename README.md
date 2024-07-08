# Draw: Interactive whiteboarding

This repository contains an example application for the [Lazagna](https://github.com/jypma/lazagna/) framework. It is a real-time multi-user drawing application that demonstrates how to write a command pattern-based rendering pipeline. If you want to run it, you'll need to start several services.

## Prerequisites

You'll need `sbt` and `docker`.

You also need to register for a Github OAuth app, so you can log in to the application. Once you have a client ID and secret, create a file `draw-server/config.yaml` with the following content:

```yaml
github:
  clientId: MY-CLIENT-ID
  secret: MY-SECRET
```

## Cassandra
Storage is provided by Cassandra. During development, start cassandra locally using

```sh
pushd draw-server
docker-compose up -d
popd
```

This can take up to two minutes to fully start initially. You can leave Cassandra running in the background.

Cassandra will store its data under `draw-server/target/cassandra-data` so your precious drawings will survive restarts. Docker will make them owned by user and group `999`, though (since that's what Cassandra runs as).

## Server

In one console run:

```sh
sbt
project server
~reStart
```

## Client (sbt)

In another console run:

```sh
sbt
project client
~fastLinkJS
```

## Client (npm, vite)

In a third console run:

```sh
cd draw-client
npm install # you only need this once.
npm run dev
```

The latter will open the example at `http://localhost:5173/`, which you can open in your web browser. Multiple users (web browsers) can edit the drawing simultaneously.

# Building the docker container

You can set up the server to serve both client and API from a docker container (you'll still need cassandra as well). In order to build the docker container, run `docker-publish-local.sh`.

From there, you can also push the resulting container to a registry using normal `docker` commands. If you want to use `docker-compose`, use the following as starting point:

```yaml
services:
  cassandra:
      image: cassandra:4.1.4
      ports:
        - "9042:9042"
      environment:
        - "MAX_HEAP_SIZE=256M"
        - "HEAP_NEWSIZE=128M"
      restart: always
      volumes:
        - ./cassandra_data:/var/lib/cassandra
      healthcheck:
        test: ["CMD", "cqlsh", "-u cassandra", "-p cassandra" ,"-e describe keyspaces"]
        interval: 15s
        timeout: 10s
        retries: 10

  cassandra-load-keyspace:
      image: cassandra:4.1.4
      depends_on:
        cassandra:
          condition: service_healthy
      volumes:
        - ./schema.cql:/schema.cql
      command: /bin/bash -c "echo loading cassandra keyspace && cqlsh cassandra -f /schema.cql"

  draw:
    image: jypmadoc/draw:0.1.0-SNAPSHOT
    ports:
      - "8080:8080"
      - "443:8443"
    depends_on:
      cassandra-load-keyspace:
        condition: service_completed_successfully
    volumes:
      - ./config.yaml:/opt/docker/config.yaml
```

The `schema.cql` file is available under `draw-server/src/main/resources`, and `config.yaml` you've created already. However, you need an additional entry in `config.yaml` when running in docker:

```yaml
github:
  clientId: MY-CLIENT-ID
  secret: MY-SECRET
cassandra:
  hostname: cassandra
http:
  cookieDomain: my.domain.example.net
```

This makes sure that the `draw` server finds Cassandra as `cassandra` (which is the name of the cassandra service under docker-compose). The Cassandra hostname defaults to `localhost` otherwise.

Also set the cookie domain to the actual domain you'll be serving the application from.

# Icons

Source: https://github.com/leungwensen/svg-icon
-`npm install`
- Copy packaged SVG icon symbol collections from `dist/sprite/symbol` to `public/symbols`

# Packaging

- In the client directory, run `npm run build`
- Now, you can load the latest client directly from the server on `https://localhost:8443` after `reStart`.
- When logging in, change the port after redirect to 8443 instead of 5173, to ensure you're working directly on the backend serving HTML (and not vite).

# Random notes (please ignore this section)

### Timeline
- Full re-render from underlying non-pruned event store
- Snapshot the DrawingState every 1000 events
- Present a compressed actual time line (with a play button!)
- Render from snapshot and subsequent events
- Read-only view, but we can allow specific objects (or a selection) to be "rescued" back into the live editor

### Manual layout
- Widget has padding
- Icon (or any widget, e.g. note), moveable at will (but keep padding in tact)
  -> Push and shove moving?
- Arrows between widgets

### Automatic layout
- Band
  -> weight on distance
  -> preferred angle
  -> weight on preferred angle
- Circular layout: bands to center (first element) and between each other
- Horizontal and Vertical layout

- Band from i1 to i2. Distance between i1 and i2 is `d` distance weight `w_d`, angle weight `w_a`, and abs deviation from preferred angle `da`
  Distance d is `d = sqrt((i1.x - i2.x)^2 + (i1.y - i2.y)^2)`
  loss is `d * w_d + da * w_a`
  loss is `sqrt((i1.x - i2.x)^2 + (i1.y - i2.y)^2) * w_d + da * w_a`
  calculate

- Other factors for loss function:
  * Style: Label: Center, keep lines same width, close to optimal width (of 2x icon?)
  * Style: Note: Justified, Top-aligned, lines exact width of note,

  * Hyphenation https://github.com/gnieh/hyphen
  * Stretch of each line (using TeX-like glue structure with badness) Glue: { size, plus, minus }. Do we need infinity  here or is big numbers enough? Or take highest order infinity that has >0.000001
  * Consider breaking lines from 0.5 stretched to 0.5 shrunk
  * Characters per line (66 optimal, 45 to 75 maxima). This includes spaces. Set glue such that line line is 33em.
  * Aspect ratio of the total text?

## OAuth: Reddit

Here: `https://github.com/reddit-archive/reddit/wiki/OAuth2`

