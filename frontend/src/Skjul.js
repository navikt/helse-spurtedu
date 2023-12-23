import "@navikt/ds-css";
import {useState} from "react";
import {
    Button,
    CopyButton,
    Detail, ErrorSummary,
    Heading, HGrid,
    HStack,
    Radio,
    RadioGroup,
    Textarea,
    TextField,
    VStack
} from "@navikt/ds-react";

const Tilstand = {
    VisSkjema: 0,
    Takk: 1
}

const Skjulevalg = {
    Url: 0,
    Tekst: 1
}

function VisSkjema({ håndterSendtInn} ) {
    const [skjulevalg, setSkjulevalg] = useState(Skjulevalg.Tekst)
    const [hemmelighet, setHemmelighet] = useState('')
    const [tilgang, setTilgang] = useState(null)
    const [sender, setSender] = useState(false)
    const [feilbeskrivelse, setFeilbeskrivele] = useState('')

    const håndterValgEndring = (val) => setSkjulevalg(val)
    const håndterSendInn = async () => {
        setSender(true)
        const response = await sendRequest(skjulevalg, hemmelighet, tilgang)
        setSender(false)
        if ('feilbeskrivelse' in response) return setFeilbeskrivele(response.feilbeskrivelse)
        håndterSendtInn(response)
    }
    let input
    switch (skjulevalg) {
        case Skjulevalg.Url:
            input = <TextField id="input" label="URL" description="Det godtas kun adresser under nav.no" placeholder="http://…" onInput={ (e) => setHemmelighet(e.target.value.trim() )} />
            break;
        case Skjulevalg.Tekst:
            input = <Textarea id="input" label="Tekst" description="Teksten kan være hva som helst, f.eks. en json-string" onInput={(e) => setHemmelighet(e.target.value.trim())}></Textarea>
            break
    }
    let feilmelding = feilbeskrivelse && <ErrorSummary heading="Du må ordne opp i dette før du kan sende inn">
        <ErrorSummary.Item href="#input">{ feilbeskrivelse }</ErrorSummary.Item>
    </ErrorSummary>

    return <HGrid gap="6" columns="auto 250px">
        <VStack gap="4">
            <div>
                <RadioGroup legend="Hva vil du skjule?" value={skjulevalg} onChange={(val) => håndterValgEndring(val)}>
                    <Radio value={ Skjulevalg.Url }>URL</Radio>
                    <Radio value={ Skjulevalg.Tekst }>Tekstinnhold</Radio>
                </RadioGroup>
            </div>
            <div>
                { input }
            </div>
            <div>
                <TextField
                    label="Tilgangstyring"
                    description="Hvem du ønsker skal kunne åpne opp hemmeligheten. Kan være en NAV-epostadresse eller en Azure AD gruppe-ID.
                    Om du ikke spesifiserer tilgang vil den være åpen for alle."
                    onInput={ (e) => setTilgang(e.target.value.trim()) }
                />
            </div>
            <div>
                <Button
                    variant="primary"
                    onClick={ håndterSendInn }
                    loading={ sender }
                >Send inn</Button>
            </div>
            { feilmelding }
        </VStack>
        <div>
            <Heading size="medium">Hva er dette?</Heading>
            <p>
                Spurte Du™ er en tjeneste for å hjelpe deg å skjule ting for andre.
            </p>
            <p>
                Ønsker du å sende noen en link til et fagsystem som har fnr/aktørId i URL?
                Null problem! Da velger du <strong>URL</strong> som det du vil skjule også vil
                du få tilbake en anonym URL du kan sende til andre.
            </p>
            <p>
                Den anonyme URL-en er <strong>ikke permanent</strong>, og kan bli slettet når som helst.
                Dessuten kan du styre hvem som kan se innholdet i hemmeligheten din.
            </p>
            <p>
                Når du besøker den anonyme URL-en så vil Spurte Du™ automatisk videresende deg
                til en hemmelige URL-en dersom tilgangen er i orden.
            </p>
        </div>
    </HGrid>
}

async function simulerTreghet() {
    return new Promise(resolve => {
        setTimeout(resolve, 1000)
    })
}

async function sendRequest(skjulevalg, hemmelighet, tilgang) {
    await simulerTreghet()
    let request = {
        påkrevdTilgang: tilgang
    }
    switch (skjulevalg) {
        case Skjulevalg.Tekst:
            request = { ...request, tekst: hemmelighet }
            break;
        case Skjulevalg.Url:
            request = { ...request, url: hemmelighet }
            break;
    }
    return fetch('/skjul_meg', {
        method: 'POST',
        headers: {
            'Accept': 'application/json',
            'Content-Type': 'application/json'
        },
        body: JSON.stringify(request)
    })
        .then(response => response.json())
}

function Kvittering({ kvittering, håndterNy }) {
    return <>
        <VStack gap="1">
            <Heading size="medium">Kvittering</Heading>
            <Button variant="secondary" onClick={håndterNy}>Sende inn ny</Button>
            <Divider/>
            <div>
                <Descriptor>ID</Descriptor>
                <Detail>{ kvittering.id }</Detail>
            </div>
            <Divider />
            <div>
                <Descriptor>Path</Descriptor>
                <Detail>{ kvittering.path }</Detail>
            </div>
            <Divider />
            <div>
                <HStack gap="1">
                    <Descriptor>URL</Descriptor>
                    <CopyButton size="small" copyText={ kvittering.url } />
                </HStack>
                <Detail>{ kvittering.url }</Detail>
            </div>
        </VStack>
    </>
}

function Descriptor({ children }) {  return <p className="mb-3 text-xl font-semibold">{children}</p>;}
function Divider() {  return <hr className="border-border-subtle" />;}
export default function Skjul() {
    const [tilstand, setTilstand] = useState(Tilstand.VisSkjema)
    const [kvittering, setKvittering] = useState(null)
    let innhold;
    switch (tilstand) {
        case Tilstand.VisSkjema:
            const håndterSendtInn = (response) => {
                console.log(`Response: ${response}`)
                console.log(JSON.stringify(response))
                setKvittering(response)
                setTilstand(Tilstand.Takk)
            }
            innhold = <VisSkjema håndterSendtInn={håndterSendtInn} />
            break;
        case Tilstand.Takk:
            const håndterNy = () => setTilstand(Tilstand.VisSkjema)
            innhold = <Kvittering kvittering={kvittering} håndterNy={håndterNy }/>
    }

    return <>{innhold}</>;
}