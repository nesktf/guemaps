# SAETA API docs
- API_URL: `"https://salta.miredbus.com.ar/rest/"`

### TODO:
- Bus times (The current API is garbage, might need some manual parsing)
- Bus prices (scrape from here `https://www.saetasalta.com.ar/saetaw/tarifas`)

## Bus groups
- URL: `"${API_URL}/gruposLineas"`

```ts
type BusEntry = {
  codLinea: string, // number as a string, i think between 0 and 1000
  descripcion: string, // description
};
type BusGroupNode = {
  codGrupo: string, // group identifier
  subGrupos: Array<BusGroupNode> | null, // subgroups, null if none
  lineas: Array<BusEntry> | null, // bus lines, null if this is just a group container
                                  // the last node in the hierarchy ALWAYS has entries
};
```

### Errcodes
- 0: ok
- 98: Suspended session
- 100: Generic error?

## Bus stops & bus route nodes
- URL: `${API_URL}/rutaLinea/${BUS_ID}`
- BUS_ID -> int. This is just `codLinea` from `BusEntry` but as a number

```ts
type BusNode = {
  latitud: number, // node lat
  longitud: number, // node lon
  parada: boolean, // if this node is a bus stop
  codigoParada: string | null, // identifier for the bus stop, null or empty if `parada == false`
  descripcionParada: string | null, // description for the bus stop, null or empty if `parad == false`
};
// GET response from ${URL}
type BusRouteResponse = {
  error: number, // see error codes below
  nodos: Array<BusNode>; // empty when ${BUS_ID} is invalid
};
```

### Errcodes
- 0: ok
- 98: Suspended session
- 100: Generic error?

### Nodes
These nodes are used to define the bus route in the map, this includes the bus stops too.

## Active buses
- URL: `${API_URL}/posicionesBuses/${BUS_ID}`
- BUS_ID -> int

```ts
type BusPos = {
  interno: string, // ${BUS_ID} as string
  latitud: number, // bus lat
  longitud: number, // bus lon
  orientacion: number, // bus direction angle
  proximaParada: string | null, // next bus stop, this is `descripcionParada` from `BusNode`
  vehiculoRampa: boolean, // has a ramp for wheelchairs
  vehiculoNoVisibles: boolean, // always false?
};
// GET response from ${URL}
type BusPosResponse = {
  error: number, // see error codes below
  posiciones: Array<BusPos>, // empty when ${BUS_ID} is invalid
};
```

### Errcodes
- 0: ok
- 98: Suspended session
- 100: Generic error?

## Check card balance
- BALANCE_URL: `${API_URL}/getSaldoCaptcha/${CARD_NUMBER}/${CAPTCHA_VALUE}`
- CAPTCHA_URL: `https://saeta.miredbus.com.ar/captcha.png?time=${MSECS}`
- CAPTCHA_URL -> string
- MSECS -> int

```ts
type CardBalance = {
  id: number, // always 0 i think
  nombre: string, // the type of balance, usually "Principal (Dinero)"
  prefijoSaldo: string, // usually empty
  sufijoSaldo: string, // usually " pasajes"
  unidadPasajes: boolean, // usually `true`
};
// GET Response from `${BALANCE_URL}`
type CardBalanceResponse = {
  error: number, // see error codes below
  estado: string, // status string, usually "ACTIVA" or "INACTIVA" (the last one i assume)
  fechaSaldo: number, // UNIX timestamp for last balance check, includes milliseconds
  nroExternoTarjeta: string, // same as ${CARD_NUMBER}
  tipoTarjeta: string, // card type, usually "COMUN", didn't check the others
  saldos: Array<{monedero: CardBalance}>, // actual balance data
};
```

### Errorcodes
- 0: ok
- 1: Invalid captcha
- 2: Invalid card number
- 3: Duplicate card number
- 98: Suspended session
- 99: Expired session
- 100: Invalid card number (again? I assume deleted)

### The captcha thing
The value for MSECS seems arbitrary? Its just the milliseconds for the current date. Add it just
in case i guess.

```ts
const MSECS = (new Date()).getMilliseconds();
```

Doing an HTTP request to `${CAPTCHA_URL}` returns raw image data in png format.
190x50 pixels, 5 alphanumeric digits (ASCII).

I assume both the captcha request and the balance request need to be made in the same session?
The request didn't work when using curl directly but it did on the browser debug console.
