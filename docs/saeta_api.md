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
- CAPTCHA_URL: `https://salta.miredbus.com.ar/captcha.png?time=${MSECS}`
- CAPTCHA_URL -> string
- MSECS -> int
- CARD_NUMBER -> int

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

## Selling points
- URL: `${API_URL}/getPuntosVenta/0`

```ts
type SellingPoint = {
  id: number,
  tipo: number | null, // check types below
  nombre: string, // name
  domicilio: string, // address
  detalleDomicilio: string|null, // looks like this is always null, can ignore
  latitud: number|null, // pos lat, check notes below
  longitud: number|null, // pos lon, check notes below
};

type SellingPointResponse = {
  error: number, // see errcodes belo
  version: number, // map version i guess, i assume this changes when a new point is added
  sinCambios: boolean, // i assume this changes when the version changes? was always true
  puntosVenta: Array<SellingPoint>,
};
```

### Types
- 1|null: "Venta de Tarjetas y Recargas"
- 2: unused
- 3: "PERS". Don't know what this means, appears to always have broken coords (check notes below)
- 4: "ATM"

### Errcodes
- 0: ok
- 98: Suspended session
- 100: Generic error?

### lat lon notes
Some selling points have broken positions; null values, values out of range (lat outside of 
-24.XX, lon outside of -65.XX) (fix your shitty API please). Ignore those with null coordinates
and try to sanitize those with coordinates out of range (*10 or /10 until it is in range)

## Config API
- URL: `${API_URL}/getConfiguracion`

```ts
type ConfigJsonData = {
  titulo: string,
  icono: string,
  tipo: string,
  contenidoJson: Array<{icon: string, key: string, value: string}>,
};

type ConfigResponse = {
    error: int,
    menu: Array<ConfigJsonData>
    // ... more things we don't care about
};
```

### Errcodes
- 0: ok
- 98: Suspended session
- 100: Generic error?

### Ticket prices
Always the first item in the `ConfigResponse::menu` array. Right now this look something like this:

```js
{
  "titulo": "Tarifas",
  "icono": "pricetag",
  "tipo": "tarifas",
  "contenidoJSON": [
    {
      "icon": "pricetag",
      "key": "Boleto Común",
      "value": "$1.450,00"
    },
    {
      "icon": "pricetag",
      "key": "Abono Social",
      "value": "$870,00"
    }
  ]
}
```

We only care about the key value pairs
