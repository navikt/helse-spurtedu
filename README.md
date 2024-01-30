spurte du?
==========

tilbyr en tjeneste for å støtte anonym dyplenking eller deling av tekst.

## Bruksområder

- Sende en tekst til en bestemt mottaker
- Skjule en URL

### Eksempler

Når man søker på et fnr/aktørId i [spanner](https://github.com/navikt/helse-spanner) så vil
Spanner putte verdien i SpurteDu, og bruke UUID'en fra SpurteDu i URL i nettleseren. 
Verdien er scopet til den påloggende brukers epostadresse.

For eksempel om du søker etter fnr `1234` og SpurteDu returnerer en uuid `adf01` så vil
Spanner oppdatere URL til `https://spanner/person/adf01`. 
Spanner vil så slå opp person `adf01` i Spleis med OBO-token fra den påloggende brukeren. 
Spleis vil så slå opp i SpurteDu med OBO-token og "veksle" `adf01` med `1234`.

På den måten kan vi trygt dele url `https://spanner/person/adf01` i logger og Slack uten at 
det er risiko for at vi forteller noe om _hvem_ URL-en peker til.

## API

Man kan velge å skjule en URL eller tekst.

Ved visning av en URL-hemmelighet så vil SpurteDu automatisk videresende deg til den skjulte URL-en.

Ved visning av tekst så vil SpurteDu svare med en JSON-respons:
```json
{
  "text": "<hemmelig verdi>"
}
```

### Opprette verdier
Å opprette en hemmelig verdi er veldig enkelt. Det krever ikke autentisering:

#### Opprette en URL-videresending scopet til en AzureAD-gruppe
```
curl -X POST -d '{ "url": "https://fagsystem/med/fnr/i/url", "påkrevdTilgang": "<en azureAD-gruppe-ID>" }' \
  https://spurtedu/skjul_meg
```

#### Opprette en tekst-hemmelighet til en konkret mottaker
```
curl -X POST -d '{ "tekst": "hei det var lenge siden", "påkrevdTilgang": "navn.etternavn@nav.no" }' \
  https://spurtedu/skjul_meg
```

#### Opprette en åpen verdi som kan sees av alle
```
curl -X POST -d '{ "tekst": "godt nytt år" }' \
  https://spurtedu/skjul_meg
```

### Vise verdier

```
curl -H "Authorization: Bearer <jwt>" https://spurtedu/vis_meg/<uuid>
```

Man kan også besøke vis-meg-URLen i en nettleser.

## Autorisering

Dersom hemmeligheten krever autentisering, og dette ikke er gjort, vil SpurteDu videresende 
deg til pålogging via WonderWall.

For all autorisert tilgang så kreves det `Bearer`-token i `Authorization`-header.

# Henvendelser
Spørsmål knyttet til koden eller prosjektet kan stilles som issues her på GitHub.

## For NAV-ansatte
Interne henvendelser kan sendes via Slack i kanalen #team-bømlo-værsågod.
