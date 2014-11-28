import iso19139.SummaryFactory

def isoHandlers = new iso19139.Handlers(handlers, f, env)

SummaryFactory.summaryHandler('gmd:applicationSchemaInfo', isoHandlers)

isoHandlers.addDefaultHandlers()

handlers.roots("gmd:applicationSchemaInfo")