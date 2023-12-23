import Skjul from "./Skjul";
import {Box, InternalHeader, Page, Spacer} from "@navikt/ds-react";

export default function App() {
   return (
       <Page footer={
           <Box background="surface-neutral-moderate" padding="8" as="footer">
               <Page.Block gutters width="lg">
                   © Team Bømlo™
               </Page.Block>
           </Box>
       }>
           <InternalHeader>
               <InternalHeader.Title href="/">Spurte du?</InternalHeader.Title>
               <Spacer />
               <InternalHeader.User name="Ola Normann" />
           </InternalHeader>
           <Box background="surface-neutral-moderate" padding="8" as="header">
               <Page.Block gutters width="lg">
                   Skjul URL eller tekst-innhold
               </Page.Block>
           </Box>
           <Box
               background="surface-alt-3-moderate"
               padding="8"
               paddingBlock="16"
               as="main"
           >
               <Page.Block gutters width="lg">
                   <Skjul />
               </Page.Block>
           </Box>
       </Page>
   )
}