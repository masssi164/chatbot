Streaming-Events und Payloads der OpenAI Responses API v1
Allgemeine Ablauf-Ereignisse
response.created
Beschreibung: Dieses Event signalisiert den Start einer neuen Antwort. Es wird als erstes gesendet, sobald die Streaming-Antwort erstellt wurde und in den Status „in_progress“ übergeht
v03.api.js.langchain.com
masaic-ai.mintlify.app
.
Beispiel-Payload:
{
  "type": "response.created",
  "response": {
    "id": "resp_abcdef123",
    "status": "in_progress"
  },
  "event_id": "event_XYZ789"
}
Besonderheiten: Enthält eine neue Antwort-ID (resp_...), die verwendet werden kann, um den Verlauf (Konversation) fortzusetzen
blog.robino.dev
blog.robino.dev
. Dieses Event erscheint bei allen Modellen zu Beginn des Streams, einschließlich mcp.
response.in_progress
Beschreibung: Dieses Event zeigt an, dass die Antwort fortlaufend generiert wird. Es wird für jeden neuen Chunk des generierten Inhalts gesendet, solange die Ausgabe noch läuft
masaic-ai.mintlify.app
masaic-ai.mintlify.app
. In der Regel begleitet jedes response.in_progress-Event einen Daten-Chunk (z. B. Textfragment) im Stream.
Beispiel-Payload:
{
  "type": "response.in_progress",
  "response": {
    "id": "resp_abcdef123",
    "status": "in_progress"
  }
}
Besonderheiten: Dient hauptsächlich als Status-Update, hat selbst aber keine Nutzdaten außer dem Status. Bei jedem neu eintreffenden Inhalt bleibt der Status in_progress. Dieses Event ist ebenfalls modelleunabhängig (tritt auch bei mcp auf).
response.completed
Beschreibung: Dieses Event zeigt an, dass die Antwort vollständig abgeschlossen wurde
masaic-ai.mintlify.app
. Es wird gesendet, wenn der finale Ausgabe-Chunk empfangen wurde (typischerweise wenn finish_reason: "stop" erreicht ist)
masaic-ai.mintlify.app
.
Beispiel-Payload:
{
  "type": "response.completed",
  "response": {
    "id": "resp_abcdef123",
    "status": "completed"
  }
}
Besonderheiten: Enthält die endgültige Antwort-ID. Bei gestreamten Antworten wird hierüber beispielsweise das id für Folgeturns bereitgestellt (für previous_response_id)
blog.robino.dev
. Dieses Event markiert das Ende des Streams (vergleichbar mit einem „Done“-Signal) für erfolgreiche Komplettierung.
response.incomplete
Beschreibung: Dieses Event kennzeichnet, dass die Antwort vorzeitig beendet wurde, weil z. B. ein Token-Limit erreicht wurde. Es wird anstelle von response.completed gesendet, wenn der finale Abschlussgrund length (oder ähnlich) ist
masaic-ai.mintlify.app
. Dies entspricht einem finish_reason: "length" im Chat-Completions-Modell.
Beispiel-Payload:
{
  "type": "response.incomplete",
  "response": {
    "id": "resp_abcdef123",
    "status": "incomplete"
  }
}
Besonderheiten: Signalisiert dem Client, dass die Antwort unvollständig abgebrochen wurde (z. B. bei Überschreiten der Maximaltoken)
masaic-ai.mintlify.app
. In solchen Fällen kann die Anwendung entscheiden, ob sie ggf. eine Anschlussanfrage stellt.
response.output_item.added
Beschreibung: Dieses Event zeigt an, dass ein neuer Ausgabe-Abschnitt beginnt
masaic-ai.mintlify.app
. Jedes Mal, wenn die Streaming-Antwort ein neues Output-Item (z. B. ein neuer inhaltlicher Block) startet, wird dieses Event gesendet, bevor die eigentlichen Inhalte streamen. Es markiert die Grenze zwischen verschiedenen Antwortteilen.
Beispiel-Payload:
{
  "type": "response.output_item.added",
  "item_id": "msg_1",
  "output_index": 0
}
Besonderheiten: output_item.added wird vor allem relevant, wenn die Antwort mehrere Teile hat (Multi-Content-Ausgaben, z. B. Text und Bild). Es kündigt einen neuen Teil an, der im Stream folgt. In vielen einfachen Antworten (einzelner Text) kann dieses Event entfallen oder implizit sein; es ist eher bei Multi-Output oder Tool-Ausgaben relevant.
response.output_item.done
Beschreibung: Dieses Event wird gesendet, sobald ein Ausgabe-Item fertiggestellt ist
masaic-ai.mintlify.app
. Es fasst alle zuvor gestreamten Chunks zu diesem Item zusammen und signalisiert das Ende dieses inhaltlichen Blocks (z. B. Ende der Textantwort oder Ende einer Tool-Antwort).
Beispiel-Payload:
{
  "type": "response.output_item.done",
  "item": {
    "id": "msg_1",
    "object": "message_output",
    "content": "Hallo Welt!"
  }
}
Besonderheiten: Das item-Objekt enthält meist die konsolidierte Ausgabe dieses Blocks (z. B. der komplette Text). Dieses Event ist nützlich, um die Ausgabe in logische Abschnitte zu gliedern (etwa wenn erst Tool-Ergebnisse geliefert werden und danach die eigentliche Antwort). Es erscheint in Streams mit komplexeren Ausgaben häufiger und hilft bei Screenreadern, da jeder Abschnitt abgeschlossen gemeldet wird.
response.content_part.added
Beschreibung: Dieses Event ist eine feiner granulare Fortschrittsanzeige, die signalisiert, dass ein neuer Teil innerhalb eines Inhalts zu streamen beginnt
masaic-ai.mintlify.app
. (Dies ist eine Erweiterung in der Open-Responses-Layer und hat kein direktes Pendant in der Chat-API.)
Beispiel-Payload:
{
  "type": "response.content_part.added",
  "item_id": "msg_1",
  "content_index": 0
}
Besonderheiten: Tritt hauptsächlich bei multimedialen oder mehrteiligen Inhalten auf und ermöglicht es, den Fortschritt innerhalb eines einzelnen Output-Items nachzuverfolgen
masaic-ai.mintlify.app
. Für Standard-Textausgaben ist dieses Event selten relevant. Screenreader können dies nutzen, um anzukündigen, dass z. B. ein neuer Abschnitt innerhalb der laufenden Antwort beginnt.
response.content_part.done
Beschreibung: Dieses Event signalisiert das Ende eines Content-Teils innerhalb eines Ausgabe-Items
masaic-ai.mintlify.app
. Es markiert, dass der aktuell gestreamte Teil (Content-Part) vollständig ist.
Beispiel-Payload:
{
  "type": "response.content_part.done",
  "item_id": "msg_1",
  "content_index": 0
}
Besonderheiten: Wie content_part.added ist dies eine feinere interne Fortschrittsanzeige. Es erscheint typischerweise nach Abschluss eines Abschnitts, bevor ggf. ein neuer content_part.added oder das output_item.done kommt. In der Praxis kommen content_part Events v. a. bei Multi-Content-Antworten oder speziellen Modellen vor; normale Text-Antworten senden meist direkt output_text und dann output_item.done.
Inhalts- und Ausgabe-Ereignisse
response.output_text.delta
Beschreibung: Dies ist das Haupt-Daten-Event für Textinhalte. Jeder Aufruf liefert einen Text-Delta – also den neuesten Text-Token oder -Abschnitt der Antwort
blog.robino.dev
masaic-ai.mintlify.app
. Das Modell streamt seinen Text Stück für Stück über mehrere output_text.delta Events. Der delta-Wert enthält jeweils die Zeichenfolge, die seit dem letzten Chunk hinzugekommen ist
blog.robino.dev
.
Beispiel-Payload:
{
  "type": "response.output_text.delta",
  "delta": "Hallo, ",
  "item_id": "msg_1",
  "content_index": 0,
  "output_index": 0
}
Besonderheiten: Dieses Event tritt häufig auf – bei jeder Ausgabe neuer Text-Tokens. Clients können die delta-Strings konkatonieren, um den gesamten Antworttext aufzubauen
blog.robino.dev
. Für Screenreader kann damit laufend vorgelesen werden, was generiert wird. (Hinweis: Falls logprobs angefordert wurden, können solche Wahrscheinlichkeiten hier ebenfalls enthalten sein, was aber im Normalfall nicht der Fall ist.)
response.output_text.done
Beschreibung: Dieses Event zeigt an, dass der Text-Output abgeschlossen ist
masaic-ai.mintlify.app
. Es wird gesendet, sobald das letzte output_text.delta gesendet wurde und kein weiterer Text folgt. Oft entspricht dies dem Ende eines Textblocks (z. B. einer Chat-Antwort) innerhalb der Ausgabe.
Beispiel-Payload:
{
  "type": "response.output_text.done",
  "item_id": "msg_1",
  "content_index": 0
}
Besonderheiten: Nach diesem Event folgt in der Regel entweder ein response.output_item.done (wenn der gesamte Antwortblock fertig ist) oder neue Events für weitere Inhalte. output_text.done dient primär der Abgrenzung, wann der aktuelle Text vollständig ist
masaic-ai.mintlify.app
.
response.refusal.delta
Beschreibung: Dieses Event erscheint, wenn das Modell anstelle einer normalen Antwort einen Moderations- oder Verweigerungstext streamt. Es funktioniert analog zu output_text.delta, streamt aber den Text einer Ablehnung/Moderation Schritt für Schritt
masaic-ai.mintlify.app
. Dies kann passieren, wenn die Anfrage gegen Richtlinien verstößt und das Modell eine Ablehnung formuliert.
Beispiel-Payload:
{
  "type": "response.refusal.delta",
  "delta": "Entschuldigung, dazu kann ich keine Auskunft geben",
  "item_id": "msg_2",
  "content_index": 0
}
Besonderheiten: refusal.delta tritt nur in Fällen auf, in denen eine Inhaltsmoderation greift oder das Modell die Antwort verweigert. Die gestreamten Chunks ergeben zusammen die Verweigerungsnachricht. Diese Events sind eine OpenAI-Erweiterung gegenüber der klassischen Chat-API, um auch Ablehnungen sauber streamen zu können
masaic-ai.mintlify.app
.
response.refusal.done
Beschreibung: Dieses Event signalisiert, dass der Verweigerungstext vollständig ist
masaic-ai.mintlify.app
. Es markiert das Ende der gestreamten Moderations-/Ablehnungsnachricht, analog zum Abschluss eines normalen Text-Streams.
Beispiel-Payload:
{
  "type": "response.refusal.done",
  "item_id": "msg_2",
  "content_index": 0
}
Besonderheiten: Nach refusal.done folgt in der Regel direkt ein response.completed oder response.failed (die gesamte Anfrage wird beendet, da keine inhaltliche Antwort erfolgt). Für Screenreader kann dieses Event genutzt werden, um dem Nutzer klarzumachen, dass die Ablehnungsnachricht zu Ende ist.
response.reasoning_summary.delta (optional)
Beschreibung: Dieses Event kann erscheinen, wenn das Modell seine interne Begründung/Denkschritte als Text ausgibt (z. B. in einem Debug- oder Erklärmodus). Es funktioniert ähnlich wie output_text.delta, liefert aber einen Gedankenzusammenfassung-Text in Echtzeit. (Beispielsweise kann ein mcp-fähiges Modell einen Reasoning-Trace ausgeben, wenn aktiviert
jamesrochabrun.medium.com
.)
Beispiel-Payload:
{
  "type": "response.reasoning_summary.delta",
  "delta": "(Denke nach: Dafür brauche ich ein Tool...)",
  "item_id": "msg_reason_1"
}
Besonderheiten: Standardmäßig geben Modelle diese Events nicht aus – sie müssen durch spezielle Einstellungen oder Modelle aktiviert werden. Sie sind vor allem für Entwickler oder erweiterte UIs gedacht, um die Denkschritte des Modells transparent zu machen
jamesrochabrun.medium.com
. Falls vorhanden, würde analog dazu ein response.reasoning_summary.done das Ende dieser Begründungs-Ausgabe markieren.
Werkzeug- und Funktionsaufruf-Ereignisse
response.function_call_arguments.delta
Beschreibung: Dieses Event wird verwendet, wenn das Modell eine Funktionsaufruf-Anweisung generiert (entspricht dem function calling Feature der Chat-API). Während der Modellausgabe werden die Funktionsargumente schrittweise gestreamt – jedes delta enthält einen Teil der Argumente-JSON
masaic-ai.mintlify.app
. Beispielsweise, wenn das Modell {"name": "get_weather", "arguments": {"city": "Ber... generiert, kämen diese Stücke nacheinander.
Beispiel-Payload:
{
  "type": "response.function_call_arguments.delta",
  "delta": "{\"city\": \"Berlin",
  "item_id": "tool_call_1",
  "output_index": 0
}
Besonderheiten: Diese Events treten nur auf, wenn man Modelle verwendet, die Funktionsaufrufe unterstützen, und auch nur, wenn das Modell tatsächlich einen Tool/Funktionsaufruf vornimmt. Die Argumente werden oft in mehreren Teilen geschickt, da der Name und die Argumente als JSON fragmentiert kommen
adalflow.sylph.ai
adalflow.sylph.ai
. Der item_id referenziert den Aufruf-Vorgang.
response.function_call_arguments.done
Beschreibung: Dieses Event markiert, dass die Funktionsaufruf-Argumente vollständig sind
masaic-ai.mintlify.app
. D. h. das Modell hat die Übertragung des Funktionsaufrufs (Name und Argumente) abgeschlossen. Zu diesem Zeitpunkt kann der Aufruf an ein entsprechendes Tool/Funktion ausgeführt werden.
Beispiel-Payload:
{
  "type": "response.function_call_arguments.done",
  "item_id": "tool_call_1",
  "output_index": 0
}
Besonderheiten: Nach ...arguments.done weiß der Client, dass jetzt alle nötigen Informationen für den Toolaufruf vorliegen
masaic-ai.mintlify.app
. Typischerweise wird daraufhin der eigentliche Funktions- oder Tool-Call ausgeführt. Bei der Nutzung der Responses API übernimmt dies bei eingebauten Tools OpenAI intern; bei eigenen Tools (MCP) der Entwickler.
response.{tool_name}.in_progress
Beschreibung: Diese Events signalisieren den Beginn eines Tool-Einsatzes. {tool_name} wird durch den Namen des Tools ersetzt, z. B. web_search_call.in_progress für das Websuche-Tool. Das Event wird gesendet, sobald das Modell einen entsprechenden Tool-Aufruf initiiert – es zeigt, dass die Verarbeitung dieses Tools startet
masaic-ai.mintlify.app
masaic-ai.mintlify.app
.
Beispiel-Payload:
{
  "type": "response.web_search_call.in_progress",
  "item_id": "tool_call_2",
  "output_index": 0
}
Besonderheiten: Dieses „in_progress“ Event gehört zu den Kontroll-Events (Steuer-Ereignissen). Es enthält meist nur Metadaten (z. B. eine Aufruf-ID)
masaic-ai.mintlify.app
. Es tritt nur bei Modellen/Anfragen auf, die Tools verwenden (z. B. mcp oder bestimmte integrierte Tools). Screenreader könnten hier z. B. ankündigen: "Das Modell nutzt jetzt das Tool XYZ...".
response.{tool_name}.executing
Beschreibung: Dieses Event zeigt an, dass das aufgerufene Tool nun aktiv ausgeführt wird
masaic-ai.mintlify.app
masaic-ai.mintlify.app
. Während in_progress den Start markiert, kann executing signalisieren, dass das Tool tatsächlich gerade arbeitet (z. B. Suche läuft).
Beispiel-Payload:
{
  "type": "response.web_search_call.executing",
  "item_id": "tool_call_2",
  "output_index": 0
}
Besonderheiten: Ebenfalls ein Tool-Lifecycle-Event. Nicht jedes Tool sendet möglicherweise ein separates executing – es ist vor allem bei längeren Aktionen sinnvoll. Gibt dem Client die Möglichkeit, z. B. einen Ladezustand anzuzeigen ("Tool wird ausgeführt...")
masaic-ai.mintlify.app
.
response.{tool_name}.completed
Beschreibung: Dieses Event signalisiert, dass die Tool-Ausführung abgeschlossen ist
masaic-ai.mintlify.app
masaic-ai.mintlify.app
. Das bedeutet, das aufgerufene Tool hat ein Ergebnis geliefert oder seine Aufgabe beendet.
Beispiel-Payload:
{
  "type": "response.web_search_call.completed",
  "item_id": "tool_call_2",
  "output_index": 0
}
Besonderheiten: Nachdem dieses Event empfangen wurde, folgen meist wieder normale Daten-Events, z. B. Text (output_text.delta), in denen das Modell das Ergebnis des Tools verarbeitet oder präsentiert. Die Tool-Lifecycle-Events (in_progress/executing/completed) bieten transparente Zwischenschritte, die besonders beim mcp-Modell mit externen Tools auftreten
masaic-ai.mintlify.app
masaic-ai.mintlify.app
.
response.mcp_call.in_progress
Beschreibung: Speziell für das Multi-Content-Provider-Modell (mcp) zeigt dieses Event an, dass ein Aufruf an einen externen MCP-Toolserver gestartet wurde. Ähnlich den Tool-Events oben markiert es, dass das Modell jetzt einen remote Tool Call initiiert
jamesrochabrun.medium.com
. (Beim MCP-Modell können eigene Tools über einen Server angebunden sein.)
Beispiel-Payload:
{
  "type": "response.mcp_call.in_progress",
  "item_id": "ext_tool_1",
  "output_index": 0
}
Besonderheiten: Im Grunde ähnlich zu response.{tool_name}.in_progress, jedoch generisch für beliebige vom Entwickler gehostete Tools über das MCP-Protokoll. Es signalisiert den Beginn eines solchen externen Aufrufs. Der item_id hilft, die folgenden Argument- und Abschluss-Events diesem Aufruf zuzuordnen
jamesrochabrun.medium.com
.
response.mcp_call.arguments.delta
Beschreibung: Dieses Event wird während eines MCP-Toolaufrufs gesendet, um die Argumente für den externen Tool-Aufruf schrittweise zu übertragen
jamesrochabrun.medium.com
. Es entspricht funktional dem function_call_arguments.delta, nur dass hier der Aufruf an einen MCP-Connector gerichtet ist.
Beispiel-Payload:
{
  "type": "response.mcp_call.arguments.delta",
  "delta": "{\"query\": \"Wetter in Berlin",
  "item_id": "ext_tool_1",
  "output_index": 0
}
Besonderheiten: Tritt nur innerhalb von MCP-Szenarien auf. Das Modell sendet die aufbereiteten Argumente (meist JSON) an den externen Tool-Endpunkt. Entwickler können diese Chunks nutzen, um den fortschreitenden Bau des Requests nachzuvollziehen. Sobald alle Argumente gesendet wurden, folgt das mcp_call.completed Event.
response.mcp_call.completed
Beschreibung: Dieses Event zeigt an, dass der MCP-Toolaufruf erfolgreich abgeschlossen wurde
jamesrochabrun.medium.com
. D. h. das externe Tool hat geantwortet, und das Ergebnis steht dem Modell nun zur Verfügung.
Beispiel-Payload:
{
  "type": "response.mcp_call.completed",
  "item_id": "ext_tool_1",
  "output_index": 0
}
Besonderheiten: Nach Eintreffen dieses Events kann das Modelltypischerweise die Resultate des Tools verarbeiten und als Text oder anderen Content zurückliefern. Für den Entwickler signalisiert es: Der MCP-Connector-Aufruf ist durchgelaufen
jamesrochabrun.medium.com
. Fehler während des MCP-Aufrufs würden statt completed vermutlich ein Fehler-Event (z. B. response.failed) auslösen.
response.mcp_list_tools.completed
Beschreibung: Dieses spezielle Event tritt auf, wenn das Modell über MCP seine verfügbaren Tools abgefragt hat und die Liste erfolgreich erhalten wurde
jamesrochabrun.medium.com
. Es markiert also den Abschluss einer Tool-Listing-Operation. (MCP-Modelle können zu Beginn erfragen, welche Tools vorhanden sind.)
Beispiel-Payload:
{
  "type": "response.mcp_list_tools.completed",
  "item_id": "ext_tool_list_1",
  "output_index": 0
}
Besonderheiten: Nach diesem Event kennt das Modell die Tool-Liste. Oft geschieht dies vor dem ersten Toolgebrauch. Das Event an sich enthält eventuell intern die aufgelisteten Tools; nach außen wird aber primär signalisiert "Tools verfügbar". In der Streaming-Sequenz kann dieses Event dem Nutzer nicht angezeigt werden (da es rein interne Verwaltung ist), aber es ist Teil des Streams. Für Screenreader hat es inhaltlich keine Ausgabe, es ist eher ein meta-event.
Fehler-Ereignisse
response.failed
Beschreibung: Dieses Event zeigt einen Fehlschlag der Antwort-Erstellung an
openrouter.ai
openrouter.ai
. Es wird gesendet, wenn ein Fehler auftritt, nachdem die Stream-Ausgabe bereits begonnen hat (z. B. ein Serverfehler während der Generierung). Das Event enthält Details zum Fehler und markiert den Abbruch der Antwort.
Beispiel-Payload:
{
  "type": "response.failed",
  "response": {
    "id": "resp_abcdef123",
    "status": "failed",
    "error": {
      "code": "server_error",
      "message": "Internal server error"
    }
  }
}
Besonderheiten: Hier ist der Fehler in response.error eingebettet und status auf "failed". Das Stream bleibt offen bis dieses Event gesendet wurde, dann wird es geschlossen
openrouter.ai
openrouter.ai
. Für den Entwickler ist wichtig: HTTP-Status bleibt 200 OK (da Streaming schon lief), aber dieses Event signalisiert den Fehlerzustand und ein finish_reason: "error" entsprechend
openrouter.ai
.
response.error
Beschreibung: Dieses Event repräsentiert einen Fehler während der Antwortgenerierung (z. B. Regelverstoß, Rate Limit), meist bevor ein response.failed käme
openrouter.ai
. Es enthält einen error-Block mit Code und Meldung, jedoch ohne in ein response-Objekt eingebettet zu sein.
Beispiel-Payload:
{
  "type": "response.error",
  "error": {
    "code": "rate_limit_exceeded",
    "message": "Rate limit exceeded"
  }
}
Besonderheiten: Dieses Event ist seltener als response.failed und wird in bestimmten Fehlerfällen gesendet
openrouter.ai
, etwa wenn ein Fehler im Verlauf der Generierung auftritt, der jedoch nicht die ganze Antwort als „failed“ markiert. Es kann auch von Tools/Moderation herrühren. Im Gegensatz zu response.failed fehlt hier ein response.id, da die Antwort selbst evtl. nicht initialisiert wurde oder separat gehandhabt wird.
error
Beschreibung: Ein rohes Fehler-Event, das von OpenAI gesendet werden kann, obwohl es in der offiziellen Doku nicht explizit aufgeführt ist
openrouter.ai
. Dieses Event hat nur ein type: "error" und trägt den Fehlercode und die Meldung direkt. Es tritt z. B. bei Authentifizierungsproblemen auf (wie ungültiger API-Key).
Beispiel-Payload:
{
  "type": "error",
  "error": {
    "code": "invalid_api_key",
    "message": "Invalid API key provided"
  }
}
Besonderheiten: Da es sich um ein undokumentiertes aber beobachtetes Event handelt
openrouter.ai
, sollten Clients darauf vorbereitet sein. Es bedeutet in der Regel, dass keine Antwort mehr folgt, da ein kritischer Fehler auftrat vor oder zu Beginn des Streams. Screenreader würden hier ggf. eine Fehlermeldung vorlesen müssen. Hinweis: Bei der Nutzung des mcp-Modells sind alle oben genannten Event-Typen relevant. Insbesondere treten dabei die Tool-bezogenen Kontroll-Events (wie response.web_search_call.* oder response.mcp_call.*) auf, die bei rein textbasierten Modellen nicht zu sehen sind. Insgesamt ermöglicht die Responses API v1 so eine sehr feingranulare Kontrolle und Nachverfolgung des Antwortprozesses – von der Erstellung über die gestreamten Inhalte bis hin zu Tool-Einsätzen und Fehlern. Dies erleichtert sowohl die Ausgabe für Nutzer (z. B. mit Screenreadern) als auch die Entwicklung komplexer Anwendungen auf Basis von OpenAIs Modellen
masaic-ai.mintlify.app
jamesrochabrun.medium.com
.






Quellen

ENTWICKLERMODUS
°


ChatGPT kann Fehler machen. Überprüfe wichtige Informationen. Siehe Cookie-Voreinstellungen.
