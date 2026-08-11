# Forwarding mit Upstream (phax/phoss-ap) statt mit dem Fork

Dieses Dokument beschreibt, **wie eine eingehende Nachricht an den Backend-Receiver weitergeleitet
würde, wenn wir statt des Fork-Forwarders den eingebauten HTTP-Forwarder von
[phax/phoss-ap](https://github.com/phax/phoss-ap) verwenden** — als Entscheidungsgrundlage für eine
mögliche Rückkehr auf Upstream.

Der Fork-spezifische Ist-Zustand ist in [CUSTOMIZATIONS.md](CUSTOMIZATIONS.md) beschrieben, die
Nachrichtenflüsse in [communication-flows.md](communication-flows.md).

> Sprache: Dieses Dokument ist bewusst deutsch, weil es sich an das Middleware-Team richtet. Die
> übrigen Fork-Dokumente sind englisch.

---

## 1. Kurzfassung

| | Fork (heute) | Upstream |
|---|---|---|
| `forwarding.mode` | `spi` (`middleware-data`) | `http_post_sync` bzw. `http_post_async` |
| Request-Body | `InboundPeppolRequest`-XML-Envelope mit Base64-SBD | **das rohe StandardBusinessDocument** |
| AS4-HTTP-Header | in `<HttpRequestHeaders>` enthalten | nicht enthalten |
| Peppol-Metadaten | eigene XML-Elemente | im SBDH des Bodys |
| Antwort | `ProcessResult`-XML | JSON (sync) bzw. nur HTTP-Status (async) |
| Eigener Code nötig | ja (`MiddlewareReceiverForwarder`) | nein |

Der Upstream-Forwarder schickt **keinen Envelope**. Der HTTP-Body ist exakt das, was im Fork
Base64-kodiert in `<ReceivedBusinessDocument>` steht. Beide Forwarder lesen dieselbe Quelle
(`DocumentPayloadManager` über `getDocumentPath()`), der Fork kodiert sie nur zusätzlich.

Belegstelle:
[HttpDocumentForwarder.java:159-167](../phoss-ap-forwarding/src/main/java/com/helger/phoss/ap/forwarding/http/HttpDocumentForwarder.java#L159-L167).
Das Modul `phoss-ap-forwarding` ist unveränderter Upstream-Code — der Fork hat dort nur die
pom-Version angehoben.

---

## 2. Der Request

```http
POST /forwarding HTTP/1.1
Content-Type: application/xml; charset=UTF-8
X-SBDH-Instance-ID: cff16df5-661a-481f-9d8b-99355864284e
Content-Length: 10695
```

- `Content-Type` ist fest `application/xml; charset=UTF-8` (`ContentType.APPLICATION_XML` aus
  HttpCore 5).
- `X-SBDH-Instance-ID` ist der **einzige** nachrichtenbezogene Header, den der Forwarder setzt.
- Der Pfad ergibt sich aus `forwarding.http.endpoint`.
- Zusätzliche Header sind konfigurierbar, aber **statisch** (siehe [Abschnitt 6](#6-konfiguration)).

---

## 3. Der Body

Das Beispiel unten ist eine real dekodierte Nachricht (10.695 Bytes, geprüft: wohlgeformtes XML,
Wurzelelement `sh:StandardBusinessDocument`). Der AP formatiert **nichts** um — er streamt die
gespeicherte Datei 1:1 (`InputStreamEntity`).

Die Einrückung dient nur der Lesbarkeit. Auf der Leitung steht das Dokument im Originallayout des
Absenders: XML-Deklaration und SBDH in einer einzigen Zeile, innerhalb des `<Invoice>` die
ursprünglichen Tabs und Zeilenumbrüche.

```xml
<?xml version="1.0" encoding="UTF-8"?>
<sh:StandardBusinessDocument xmlns:sh="http://www.unece.org/cefact/namespaces/StandardBusinessDocumentHeader">
  <sh:StandardBusinessDocumentHeader>
    <sh:HeaderVersion>1.0</sh:HeaderVersion>
    <sh:Sender>
      <sh:Identifier Authority="iso6523-actorid-upis">9913:251080</sh:Identifier>
    </sh:Sender>
    <sh:Receiver>
      <sh:Identifier Authority="iso6523-actorid-upis">9913:251085</sh:Identifier>
    </sh:Receiver>
    <sh:DocumentIdentification>
      <sh:Standard>urn:oasis:names:specification:ubl:schema:xsd:Invoice-2</sh:Standard>
      <sh:TypeVersion>2.1</sh:TypeVersion>
      <sh:InstanceIdentifier>cff16df5-661a-481f-9d8b-99355864284e</sh:InstanceIdentifier>
      <sh:Type>Invoice</sh:Type>
      <sh:CreationDateAndTime>2026-08-06T08:57:17.496+02:00</sh:CreationDateAndTime>
    </sh:DocumentIdentification>
    <sh:BusinessScope>
      <sh:Scope>
        <sh:Type>DOCUMENTID</sh:Type>
        <sh:InstanceIdentifier>urn:oasis:names:specification:ubl:schema:xsd:Invoice-2::Invoice##urn:cen.eu:en16931:2017#compliant#urn:fdc:peppol.eu:2017:poacc:billing:3.0::2.1</sh:InstanceIdentifier>
        <sh:Identifier>busdox-docid-qns</sh:Identifier>
      </sh:Scope>
      <sh:Scope>
        <sh:Type>PROCESSID</sh:Type>
        <sh:InstanceIdentifier>urn:fdc:peppol.eu:2017:poacc:billing:01:1.0</sh:InstanceIdentifier>
        <sh:Identifier>cenbii-procid-ubl</sh:Identifier>
      </sh:Scope>
      <sh:Scope>
        <sh:Type>COUNTRY_C1</sh:Type>
        <sh:InstanceIdentifier>DE</sh:InstanceIdentifier>
      </sh:Scope>
    </sh:BusinessScope>
  </sh:StandardBusinessDocumentHeader>
  <Invoice xmlns:cbc="urn:oasis:names:specification:ubl:schema:xsd:CommonBasicComponents-2" xmlns:cac="urn:oasis:names:specification:ubl:schema:xsd:CommonAggregateComponents-2" xmlns="urn:oasis:names:specification:ubl:schema:xsd:Invoice-2">
    <cbc:CustomizationID>urn:cen.eu:en16931:2017#compliant#urn:fdc:peppol.eu:2017:poacc:billing:3.0</cbc:CustomizationID>
    <cbc:ProfileID>urn:fdc:peppol.eu:2017:poacc:billing:01:1.0</cbc:ProfileID>
    <cbc:ID>THV_TEST_20260720_1</cbc:ID>
    <cbc:IssueDate>2017-11-13</cbc:IssueDate>
    <cbc:DueDate>2017-12-01</cbc:DueDate>
    <cbc:InvoiceTypeCode>380</cbc:InvoiceTypeCode>
    <cbc:DocumentCurrencyCode>EUR</cbc:DocumentCurrencyCode>
    <cbc:AccountingCost>4025:123:4343</cbc:AccountingCost>
    <cbc:BuyerReference>0150abc</cbc:BuyerReference>
    <cac:AccountingSupplierParty>
      <cac:Party>
        <cbc:EndpointID schemeID="9913">251080</cbc:EndpointID>
        <cac:PartyIdentification>
          <cbc:ID>99887766</cbc:ID>
        </cac:PartyIdentification>
        <cac:PartyName>
          <cbc:Name>Schröder &amp; Häußler – Gebäudereinigung hasn’t € épistèmê</cbc:Name>
        </cac:PartyName>
        <cac:PostalAddress>
          <cbc:StreetName>Hauptstraße 1</cbc:StreetName>
          <cbc:CityName>Berlin</cbc:CityName>
          <cbc:PostalZone>10111</cbc:PostalZone>
          <cac:Country>
            <cbc:IdentificationCode>DE</cbc:IdentificationCode>
          </cac:Country>
        </cac:PostalAddress>
        <cac:PartyTaxScheme>
          <cbc:CompanyID>DE1232434</cbc:CompanyID>
          <cac:TaxScheme>
            <cbc:ID>VAT</cbc:ID>
          </cac:TaxScheme>
        </cac:PartyTaxScheme>
        <cac:PartyLegalEntity>
          <cbc:RegistrationName>SupplierOfficialName Ltd</cbc:RegistrationName>
          <cbc:CompanyID>DE1232434</cbc:CompanyID>
          <cbc:CompanyLegalForm>AdditionalLegalInformation</cbc:CompanyLegalForm>
        </cac:PartyLegalEntity>
        <cac:Contact>
          <cbc:Name>John Doe</cbc:Name>
          <cbc:Telephone>9384203984</cbc:Telephone>
          <cbc:ElectronicMail>supplier@blabla.de</cbc:ElectronicMail>
        </cac:Contact>
      </cac:Party>
    </cac:AccountingSupplierParty>
    <cac:AccountingCustomerParty>
      <cac:Party>
        <cbc:EndpointID schemeID="9913">251085</cbc:EndpointID>
        <cac:PartyIdentification>
          <cbc:ID schemeID="0002">FR23342</cbc:ID>
        </cac:PartyIdentification>
        <cac:PartyName>
          <cbc:Name>Schröder &amp; Häußler – Gebäudereinigung hasn’t € épistèmê</cbc:Name>
        </cac:PartyName>
        <cac:PostalAddress>
          <cbc:StreetName>Bräuhausstraße 1</cbc:StreetName>
          <cbc:CityName>Tutzing</cbc:CityName>
          <cbc:PostalZone>82327 </cbc:PostalZone>
          <cac:Country>
            <cbc:IdentificationCode>AT</cbc:IdentificationCode>
          </cac:Country>
        </cac:PostalAddress>
        <cac:PartyTaxScheme>
          <cbc:CompanyID>AT123456789</cbc:CompanyID>
          <cac:TaxScheme>
            <cbc:ID>VAT</cbc:ID>
          </cac:TaxScheme>
        </cac:PartyTaxScheme>
        <cac:PartyLegalEntity>
          <cbc:RegistrationName>Buyer Official Name</cbc:RegistrationName>
          <cbc:CompanyID schemeID="0183">39937423947</cbc:CompanyID>
        </cac:PartyLegalEntity>
        <cac:Contact>
          <cbc:Name>John Doe</cbc:Name>
          <cbc:Telephone>9384203984</cbc:Telephone>
        </cac:Contact>
      </cac:Party>
    </cac:AccountingCustomerParty>
    <cac:Delivery>
      <cbc:ActualDeliveryDate>2017-11-01</cbc:ActualDeliveryDate>
      <cac:DeliveryLocation>
        <cbc:ID schemeID="0088">7300010000001</cbc:ID>
        <cac:Address>
          <cbc:StreetName>Delivery street 2</cbc:StreetName>
          <cbc:AdditionalStreetName>Building 56</cbc:AdditionalStreetName>
          <cbc:CityName>Stockholm</cbc:CityName>
          <cbc:PostalZone>21234</cbc:PostalZone>
          <cbc:CountrySubentity>Södermalm</cbc:CountrySubentity>
          <cac:AddressLine>
            <cbc:Line>Gate 15</cbc:Line>
          </cac:AddressLine>
          <cac:Country>
            <cbc:IdentificationCode>SE</cbc:IdentificationCode>
          </cac:Country>
        </cac:Address>
      </cac:DeliveryLocation>
      <cac:DeliveryParty>
        <cac:PartyName>
          <cbc:Name>Delivery party Name</cbc:Name>
        </cac:PartyName>
      </cac:DeliveryParty>
    </cac:Delivery>
    <cac:PaymentMeans>
      <cbc:PaymentMeansCode name="Credit transfer">30</cbc:PaymentMeansCode>
      <cbc:PaymentID>Snippet1</cbc:PaymentID>
      <cac:PayeeFinancialAccount>
        <cbc:ID>IBAN32423940</cbc:ID>
        <cbc:Name>AccountName</cbc:Name>
        <cac:FinancialInstitutionBranch>
          <cbc:ID>BIC324098</cbc:ID>
        </cac:FinancialInstitutionBranch>
      </cac:PayeeFinancialAccount>
    </cac:PaymentMeans>
    <cac:PaymentTerms>
      <cbc:Note>TEST TEST Please Delete</cbc:Note>
    </cac:PaymentTerms>
    <cac:AllowanceCharge>
      <cbc:ChargeIndicator>true</cbc:ChargeIndicator>
      <cbc:AllowanceChargeReason>Cleaning</cbc:AllowanceChargeReason>
      <cbc:Amount currencyID="EUR">200</cbc:Amount>
      <cac:TaxCategory>
        <cbc:ID>S</cbc:ID>
        <cbc:Percent>25</cbc:Percent>
        <cac:TaxScheme>
          <cbc:ID>VAT</cbc:ID>
        </cac:TaxScheme>
      </cac:TaxCategory>
    </cac:AllowanceCharge>
    <cac:AllowanceCharge>
      <cbc:ChargeIndicator>false</cbc:ChargeIndicator>
      <cbc:AllowanceChargeReason>Discount</cbc:AllowanceChargeReason>
      <cbc:Amount currencyID="EUR">100</cbc:Amount>
      <cac:TaxCategory>
        <cbc:ID>S</cbc:ID>
        <cbc:Percent>25</cbc:Percent>
        <cac:TaxScheme>
          <cbc:ID>VAT</cbc:ID>
        </cac:TaxScheme>
      </cac:TaxCategory>
    </cac:AllowanceCharge>
    <cac:TaxTotal>
      <cbc:TaxAmount currencyID="EUR">1550.00</cbc:TaxAmount>
      <cac:TaxSubtotal>
        <cbc:TaxableAmount currencyID="EUR">5000.0</cbc:TaxableAmount>
        <cbc:TaxAmount currencyID="EUR">1250</cbc:TaxAmount>
        <cac:TaxCategory>
          <cbc:ID>S</cbc:ID>
          <cbc:Percent>25</cbc:Percent>
          <cac:TaxScheme>
            <cbc:ID>VAT</cbc:ID>
          </cac:TaxScheme>
        </cac:TaxCategory>
      </cac:TaxSubtotal>
      <cac:TaxSubtotal>
        <cbc:TaxableAmount currencyID="EUR">2000.0</cbc:TaxableAmount>
        <cbc:TaxAmount currencyID="EUR">300</cbc:TaxAmount>
        <cac:TaxCategory>
          <cbc:ID>S</cbc:ID>
          <cbc:Percent>15</cbc:Percent>
          <cac:TaxScheme>
            <cbc:ID>VAT</cbc:ID>
          </cac:TaxScheme>
        </cac:TaxCategory>
      </cac:TaxSubtotal>
    </cac:TaxTotal>
    <cac:LegalMonetaryTotal>
      <cbc:LineExtensionAmount currencyID="EUR">6900</cbc:LineExtensionAmount>
      <cbc:TaxExclusiveAmount currencyID="EUR">7000</cbc:TaxExclusiveAmount>
      <cbc:TaxInclusiveAmount currencyID="EUR">8550</cbc:TaxInclusiveAmount>
      <cbc:AllowanceTotalAmount currencyID="EUR">100</cbc:AllowanceTotalAmount>
      <cbc:ChargeTotalAmount currencyID="EUR">200</cbc:ChargeTotalAmount>
      <cbc:PayableAmount currencyID="EUR">8550</cbc:PayableAmount>
    </cac:LegalMonetaryTotal>
    <cac:InvoiceLine>
      <cbc:ID>1</cbc:ID>
      <cbc:Note>Testing note on line level</cbc:Note>
      <cbc:InvoicedQuantity unitCode="C62">10</cbc:InvoicedQuantity>
      <cbc:LineExtensionAmount currencyID="EUR">4000.00</cbc:LineExtensionAmount>
      <cbc:AccountingCost>Konteringsstreng</cbc:AccountingCost>
      <cac:InvoicePeriod>
        <cbc:StartDate>2017-12-01</cbc:StartDate>
        <cbc:EndDate>2017-12-05</cbc:EndDate>
      </cac:InvoicePeriod>
      <cac:OrderLineReference>
        <cbc:LineID>123</cbc:LineID>
      </cac:OrderLineReference>
      <cac:Item>
        <cbc:Description>Description of item</cbc:Description>
        <cbc:Name>item name</cbc:Name>
        <cac:SellersItemIdentification>
          <cbc:ID>97iugug876</cbc:ID>
        </cac:SellersItemIdentification>
        <cac:StandardItemIdentification>
          <cbc:ID schemeID="0088">7300010000001</cbc:ID>
        </cac:StandardItemIdentification>
        <cac:OriginCountry>
          <cbc:IdentificationCode>NO</cbc:IdentificationCode>
        </cac:OriginCountry>
        <cac:CommodityClassification>
          <cbc:ItemClassificationCode listID="SRV">09348023</cbc:ItemClassificationCode>
        </cac:CommodityClassification>
        <cac:ClassifiedTaxCategory>
          <cbc:ID>S</cbc:ID>
          <cbc:Percent>25.0</cbc:Percent>
          <cac:TaxScheme>
            <cbc:ID>VAT</cbc:ID>
          </cac:TaxScheme>
        </cac:ClassifiedTaxCategory>
      </cac:Item>
      <cac:Price>
        <cbc:PriceAmount currencyID="EUR">400</cbc:PriceAmount>
      </cac:Price>
    </cac:InvoiceLine>
    <cac:InvoiceLine>
      <cbc:ID>2</cbc:ID>
      <cbc:InvoicedQuantity unitCode="C62">10</cbc:InvoicedQuantity>
      <cbc:LineExtensionAmount currencyID="EUR">2000.00</cbc:LineExtensionAmount>
      <cbc:AccountingCost>Konteringsstreng</cbc:AccountingCost>
      <cac:Item>
        <cbc:Description>Description of item</cbc:Description>
        <cbc:Name>item name</cbc:Name>
        <cac:SellersItemIdentification>
          <cbc:ID>97iugug876</cbc:ID>
        </cac:SellersItemIdentification>
        <cac:StandardItemIdentification>
          <cbc:ID schemeID="0088">7300010000001</cbc:ID>
        </cac:StandardItemIdentification>
        <cac:CommodityClassification>
          <cbc:ItemClassificationCode listID="SRV">86776</cbc:ItemClassificationCode>
        </cac:CommodityClassification>
        <cac:ClassifiedTaxCategory>
          <cbc:ID>S</cbc:ID>
          <cbc:Percent>15.0</cbc:Percent>
          <cac:TaxScheme>
            <cbc:ID>VAT</cbc:ID>
          </cac:TaxScheme>
        </cac:ClassifiedTaxCategory>
      </cac:Item>
      <cac:Price>
        <cbc:PriceAmount currencyID="EUR">200</cbc:PriceAmount>
      </cac:Price>
    </cac:InvoiceLine>
    <cac:InvoiceLine>
      <cbc:ID>3</cbc:ID>
      <cbc:InvoicedQuantity unitCode="C62">10</cbc:InvoicedQuantity>
      <cbc:LineExtensionAmount currencyID="EUR">900.00</cbc:LineExtensionAmount>
      <cbc:AccountingCost>Konteringsstreng</cbc:AccountingCost>
      <cac:Item>
        <cbc:Description>Description of item</cbc:Description>
        <cbc:Name>item name</cbc:Name>
        <cac:SellersItemIdentification>
          <cbc:ID>97iugug876</cbc:ID>
        </cac:SellersItemIdentification>
        <cac:StandardItemIdentification>
          <cbc:ID schemeID="0160">873649827489</cbc:ID>
        </cac:StandardItemIdentification>
        <cac:CommodityClassification>
          <cbc:ItemClassificationCode listID="SRV">86776</cbc:ItemClassificationCode>
        </cac:CommodityClassification>
        <cac:ClassifiedTaxCategory>
          <cbc:ID>S</cbc:ID>
          <cbc:Percent>25.0</cbc:Percent>
          <cac:TaxScheme>
            <cbc:ID>VAT</cbc:ID>
          </cac:TaxScheme>
        </cac:ClassifiedTaxCategory>
        <cac:AdditionalItemProperty>
          <cbc:Name>AdditionalItemName</cbc:Name>
          <cbc:Value>AdditionalItemValue</cbc:Value>
        </cac:AdditionalItemProperty>
      </cac:Item>
      <cac:Price>
        <cbc:PriceAmount currencyID="EUR">90</cbc:PriceAmount>
      </cac:Price>
    </cac:InvoiceLine>
  </Invoice>
</sh:StandardBusinessDocument>
```

---

## 4. Feld-Mapping: wo die Envelope-Inhalte abbleiben

| Fork-Element (`InboundPeppolRequest`) | Bei Upstream |
|---|---|
| `<HttpRequestHeaders>` | **entfällt ersatzlos** — die AS4-Request-Header werden nicht weitergereicht |
| `<Sender>` | im Body: `sh:StandardBusinessDocumentHeader/sh:Sender/sh:Identifier` |
| `<Receiver>` | im Body: `sh:StandardBusinessDocumentHeader/sh:Receiver/sh:Identifier` |
| `<CountryC1>` | im Body: `sh:BusinessScope/sh:Scope[sh:Type='COUNTRY_C1']/sh:InstanceIdentifier` |
| `<InstanceIdentifier>` | im Body: `sh:DocumentIdentification/sh:InstanceIdentifier` **und** HTTP-Header `X-SBDH-Instance-ID` |
| `<DocumentTypeInstanceIdentifier>` | im Body: `sh:BusinessScope/sh:Scope[sh:Type='DOCUMENTID']/sh:InstanceIdentifier` |
| `<EBMSMessageID>` | **entfällt ersatzlos** — steht nicht im SBD (siehe [Abschnitt 7](#7-die-beiden-ids-instanceidentifier-vs-ebmsmessageid)) |
| `<ReceivedBusinessDocument>` (Base64) | ist der Body selbst, unkodiert |

Die Peppol-Metadaten gehen also **nicht verloren**, der Receiver muss sie nur selbst aus dem SBDH
lesen. Echter Informationsverlust sind nur die **AS4-HTTP-Header** und die **EBMS Message-ID**.

Die Behandlung von Umlauten und Sonderzeichen (`Schröder & Häußler – … hasn’t € épistèmê`) bleibt
identisch, da UTF-8 unverändert durchgereicht wird.

---

## 5. Antwort-Contract

Statt eines `ProcessResult`-XML erwartet Upstream **JSON**, und das nur im Sync-Modus:

```jsonc
// forwarding.mode=http_post_sync
{ "countryCodeC4": "AT" }                                     // Erfolg
{ "retry": "none", "errorMessage": "unbekannter Empfänger" }  // fachliche Ablehnung, kein Retry
```

Bei `http_post_async` wird der Response-Body **gar nicht gelesen**: HTTP 2xx = Erfolg.

Auswertung im Code
([HttpDocumentForwarder.java:178-214](../phoss-ap-forwarding/src/main/java/com/helger/phoss/ap/forwarding/http/HttpDocumentForwarder.java#L178-L214)):

| JSON-Feld | Wirkung |
|---|---|
| `countryCodeC4` | wird als C4-Ländercode ins Peppol Reporting übernommen |
| `retry` = `"none"` | `ForwardingResult.failureNoRetry(...)` — kein weiterer Zustellversuch |
| `errorMessage` | Fehlertext für Logging / Transaktionsstatus |

---

## 6. Konfiguration

```properties
forwarding.mode=http_post_sync          # oder http_post_async
forwarding.http.endpoint=http://localhost:8888/forwarding

# Optionale statische Zusatzheader (indiziert ab 1, max. 100)
forwarding.http.headers.1.name=X-Api-Key
forwarding.http.headers.1.value=...
```

Präfix ist `forwarding.` (`IDocumentForwarder.DEFAULT_CONFIG_KEY_PREFIX`); ein zweiter Forwarder
kann unter `forwarding.secondary.1.` konfiguriert werden.

Zu entfernen wären die Fork-Schlüssel `forwarding.spi.id`, `forwarding.middleware.url` und
`forwarding.middleware.insecure-tls`.

> **Wichtig:** Die Custom-Header-Werte stammen aus der Konfiguration und sind damit für alle
> Nachrichten gleich. Die EBMS Message-ID lässt sich darüber **nicht** nachrüsten.

---

## 7. Die beiden IDs: `InstanceIdentifier` vs. `EBMSMessageID`

Beide sind eindeutig — aber auf unterschiedlichen Ebenen. Das ist für Deduplizierung und
Korrelation im Receiver der zentrale Punkt.

| | `InstanceIdentifier` (SBDH) | `EBMSMessageID` (AS4) |
|---|---|---|
| Identifiziert | das **Geschäftsdokument** | die **einzelne Übertragung** |
| Vergeben von | Absender (C1/C2) beim Erzeugen des SBD | sendendem AP, pro Sendeversuch |
| Bei Wiederholung | **bleibt gleich** | **neu** |
| Reichweite | Ende-zu-Ende C1 → C4 | ein Hop C2 → C3 |
| Beispiel oben | `cff16df5-661a-481f-9d8b-99355864284e` | `d0602df0-…-88233013e670@phase4` |

### Belege im Code

`processPendingOutbound` wird **pro Sendeversuch** aufgerufen und erzeugt dabei jedes Mal eine neue
AS4-ID, während die SBDH-ID an der Transaktion hängt und unverändert bleibt
([OutboundOrchestrator.java:467-469](../phoss-ap-core/src/main/java/com/helger/phoss/ap/core/outbound/OutboundOrchestrator.java#L467-L469)):

```java
final int nNewAttemptCount = aTx.getAttemptCount () + 1;
final String sAS4MessageID = MessageHelperMethods.createRandomMessageID ();
```

Dasselbe Bild im Schema: `outbound_sending_attempt` hat `UNIQUE (as4_message_id)` — eine AS4-ID **je
Versuch** —, während `sbdh_instance_id` an der Transaktion hängt.

Das Suffix `@phase4` ist keine Identität, sondern die Signatur der sendenden Software. Der eigene AP
hängt `@phoss-ap` an
([APServletInit.java:254](../phoss-ap-core/src/main/java/com/helger/phoss/ap/core/servlet/APServletInit.java#L254)).

### Duplikaterkennung im AP

Auf zwei Ebenen, beide **nur gegen die eigene Historie** — global erzwingen kann der AP nichts, denn
die SBDH-ID vergibt der Absender:

1. **AS4-Ebene:** Tabelle `as4_duplicate_item` mit `PRIMARY KEY (message_id)`.
2. **Anwendungsebene:**
   [InboundOrchestrator.java:208-244](../phoss-ap-core/src/main/java/com/helger/phoss/ap/core/inbound/InboundOrchestrator.java#L208-L244)
   prüft `containsByAS4MessageID` **und** `containsBySbdhInstanceID` und setzt die Flags
   `is_duplicate_as4` / `is_duplicate_sbdh`.

Drei Details, die man kennen sollte:

- **Der Default ist `reject`, nicht `store_and_flag`.** In `application.properties` sind beide Zeilen
  auskommentiert, der Code fällt auf `EDuplicateDetectionMode.REJECT` zurück
  ([APCoreConfig.java:627-644](../phoss-ap-core/src/main/java/com/helger/phoss/ap/core/APCoreConfig.java#L627-L644)).
  Ein Duplikat wird also standardmäßig abgewiesen.
- `inbound_transaction` hat auf beiden Spalten **nur Indizes, keine UNIQUE-Constraint** — die
  Eindeutigkeit ist Anwendungslogik, keine DB-Garantie.
- Die Prüfung sieht nur die Live-Tabelle, und die Archivierung **löscht** die Zeile dort
  ([ArchivalManagerJdbc.java:134](../phoss-ap-db/src/main/java/com/helger/phoss/ap/db/ArchivalManagerJdbc.java#L134)).
  Die Duplikaterkennung wirkt damit nur innerhalb des Archivierungsfensters
  (`archival.scheduler.interval`, Default `24h`); ein Replay Wochen später würde nicht mehr erkannt.

### Konsequenz für den Receiver

Für **fachliche Idempotenz** ist der `InstanceIdentifier` der richtige Schlüssel, gerade weil er eine
Wiederholung übersteht. Die `EBMSMessageID` wäre dafür sogar untauglich: bei jedem Sendeversuch
derselben Rechnung ist sie eine andere, dasselbe Dokument käme also als „neu" durch.

Damit relativiert sich der Informationsverlust aus [Abschnitt 4](#4-feld-mapping-wo-die-envelope-inhalte-abbleiben):
Upstream reicht mit `X-SBDH-Instance-ID` genau die ID weiter, die für Deduplizierung und Korrelation
gebraucht wird. Die EBMS-ID fehlt nur noch für die technische Nachverfolgung eines einzelnen
AS4-Hops — dafür steht sie im AP-Log und in `inbound_transaction.as4_message_id` und ist über
`GET /api/inbound/status/{sbdhInstanceID}` abfragbar.

---

## 8. Bewertung

Eine Umstellung auf Upstream ist möglich, sobald die Middleware den Contract „rohes SBD im Body +
JSON-Antwort" akzeptiert. Der Preis:

| Verlust | Schwere | Kompensation |
|---|---|---|
| `<HttpRequestHeaders>` | mittel — abhängig davon, was der Receiver daraus liest | keine über den Forwarder; ggf. statische Header |
| `<EBMSMessageID>` | gering für Fachlogik | `X-SBDH-Instance-ID` als Korrelationsschlüssel; EBMS-ID über die Inbound-API abrufbar |
| Parsing-Aufwand | gering | Metadaten aus dem SBDH statt aus Envelope-Elementen |

Der Gewinn: kein Fork-Code mehr (`MiddlewareReceiverForwarder`,
`MiddlewareReceiverForwarderProvider`, `InboundHttpHeaderContext` und der Patch an
`Phase4InboundMessageProcessorSPI`), damit direkte Upstream-Updates ohne Merge-Aufwand.

Offener Punkt vor einer Entscheidung: **welche der AS4-HTTP-Header der Receiver tatsächlich
auswertet.** Solange das nicht geklärt ist, bleibt der Fork erforderlich.
