package de.tudresden.inf.lat.jconht.model;

import de.tudresden.inf.lat.jconht.tableau.AxiomRenamer;
import org.semanticweb.owlapi.io.OWLObjectRenderer;
import org.semanticweb.owlapi.io.ToStringRenderer;
import org.semanticweb.owlapi.model.*;
import org.semanticweb.owlapi.util.QNameShortFormProvider;
import org.semanticweb.owlapi.util.SimpleRenderer;

import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * This class describes a context ontology.
 *
 * @author Stephan Böhme
 * @author Marcel Lippmann
 */
public class ContextOntology {

    private final OWLOntologyManager ontologyManager;
    private final OWLDataFactory dataFactory;
    private final OWLOntology rootOntology;
    private final Configuration configuration;
    private final OWLAnnotationProperty isDefinedBy;
    private final OWLAnnotationProperty label;
    private final OWLLiteral objectGlobal;
    private final OWLAnnotationValue rigid;
    private final Predicate<IRI> iriIsOWLClass;
    private final Predicate<IRI> iriIsOWLObjectProperty;
    private final Predicate<IRI> iriIsObjectLevel;
    private final Predicate<IRI> iriIsRigid;
    private final Predicate<OWLEntity> entityIsOWLClass;
    private final Predicate<OWLEntity> entityIsOWLObjectProperty;
    private final Predicate<OWLClass> classIsAbstractedMetaConcept;
    private final boolean containsRigidNames;
    private OWLOntology metaOntology;
    private Map<OWLClass, OWLAxiom> objectAxiomsMap;
    //private Set<OWLClass> rigidClasses; // todo müssen wir rigid names wirklich als set speichern? welche Alternativen? siehe 10 Zeilen weiter unten


    //todo prinzipielle Frage: Wenn ich es richtig verstanden habe, dann ist die Situation folgende.
    // Ich kann z.B. die rigiden Konzepte als field "rigidConcepts" speichern, dann wird es einmal berechnet und dann als Set
    // gespeichert, als Stream kann ich es nicht speichern, da dieser nur einmal verwendet werden könnte. Vorteil:
    // muss nur einmal berechnet werden, Nachteil: Speicherbedarf des Sets, selbst wenn ich nie das Feld aufrufe.
    // Alternative: eine Methode "rigidConcepts()", die einen Stream zurückgibt. Vorteil: Kein Speicherbedarf,
    // Nachteil: wird jedes Mal neu berechnet, wenn benötigt.
    //
    // Habe ich es bis hierher soweit richtig verstanden?
    //
    // In Anlehnung an die OWLAPI, wo überall Getter, die Sets zurückgegeben hätten, mit Stream-Methoden ersetzt wurden,
    // schätze ich, ist bei Ontologien eher der Speicherbedarf problematisch. Deshalb würde ich das auch so machen.
    // Was ist deine Meinung dazu?

    /**
     * This is the standard constructor.
     *
     * @param rootOntology The correctly annotated root ontology. TODO: Specify what this actually means.
     */
    public ContextOntology(OWLOntology rootOntology, Configuration configuration) {

        ontologyManager = rootOntology.getOWLOntologyManager();
        dataFactory = ontologyManager.getOWLDataFactory();
        this.rootOntology = rootOntology;
        this.configuration = configuration;


        // Define OWLAnnotationProperties and -Values, OWLLiterals, Predicates
        isDefinedBy = dataFactory.getRDFSIsDefinedBy();
        label = dataFactory.getRDFSLabel();
        objectGlobal = dataFactory.getOWLLiteral("objectGlobal");
        rigid = dataFactory.getOWLLiteral("rigid");

        //TODO kann man das hier schöner aufschreiben?
        iriIsOWLClass =
                iri -> rootOntology.classesInSignature().map(HasIRI::getIRI).anyMatch(iri1 -> iri1.equals(iri));
        iriIsOWLObjectProperty =
                iri -> rootOntology.objectPropertiesInSignature().map(HasIRI::getIRI).anyMatch(iri1 -> iri1.equals(iri));
        entityIsOWLClass =
                entity -> rootOntology.classesInSignature().anyMatch(cls -> cls.equals(entity));
        entityIsOWLObjectProperty =
                entity -> rootOntology.objectPropertiesInSignature().anyMatch(cls -> cls.equals(entity));
        iriIsObjectLevel =
                iri -> objectSignature().map(HasIRI::getIRI).anyMatch(iri1 -> iri1.equals(iri))
                        && !(dataFactory.getOWLThing().getIRI().equals(iri)
                        || dataFactory.getOWLNothing().getIRI().equals(iri)
                        || dataFactory.getOWLBottomObjectProperty().getIRI().equals(iri)
                        || dataFactory.getOWLTopObjectProperty().getIRI().equals(iri));
        iriIsRigid =
                iri -> rootOntology
                        .axioms(AxiomType.ANNOTATION_ASSERTION)
                        .filter(axiom -> axiom.getAnnotation().getProperty().isLabel())
                        .filter(axiom -> axiom.getValue().equals(rigid))
                        .map(axiom -> axiom.getSubject().asIRI().get())
                        .anyMatch(iri1 -> iri1.equals(iri));
        classIsAbstractedMetaConcept =
                owlClass -> objectAxiomsMap.keySet().stream().anyMatch(metaConcept -> metaConcept.equals(owlClass));

        // Obtain meta ontology
        generateMetaOntology();


        // Set renderer s.t. IRIs are shortened.
        OWLObjectRenderer renderer = new SimpleRenderer();
        renderer.setShortFormProvider(new QNameShortFormProvider(ontologyManager
                .getOntologyFormat(rootOntology)
                .asPrefixOWLDocumentFormat()
                .getPrefixName2PrefixMap()));
        ToStringRenderer.setRenderer(() -> renderer);


        // Obtain object axioms map
        objectAxiomsMap = rootOntology.axioms()
                .filter(owlAxiom -> owlAxiom.annotations(isDefinedBy).count() == 1)
                .collect(Collectors.toMap(
                        owlAxiom -> dataFactory.getOWLClass(
                                owlAxiom.annotations(isDefinedBy)
                                        .findFirst()
                                        // This get() does not fail, because of the above filter().
                                        .get()
                                        .getValue()
                                        .asIRI()
                                        // This get() does not fail, because values are always IRIs ;-).
                                        .get()),
                        owlAxiom -> owlAxiom.getAxiomWithoutAnnotations()));

        // Add dual axioms to meta ontology
        if (configuration.useDualization()) {
            addDualAxiomsToMetaOntology();
        }

        // Add repleted axioms to meta ontology
        if (configuration.useRepletion()) {
            addRepletionAxiomsToMetaOntology();
        }


        // Are there rigid names?
        containsRigidNames = rootOntology.axioms(AxiomType.ANNOTATION_ASSERTION)
                .filter(axiom -> axiom.getAnnotation().getProperty().isLabel())
                .filter(axiom -> axiom.getValue().equals(rigid))
                .findAny().isPresent();
    }

    public ContextOntology(OWLOntology rootOntology) {

        this(rootOntology, new Configuration());

    }

    public void clear() {
        ontologyManager.removeOntology(metaOntology);
        //TODO probably more to do here

        System.out.println("ContextOntology cleared.");
    }

    /**
     * This method generates the meta ontology.
     */
    private void generateMetaOntology() {

        try {
            //TODO hier auch wieder die Frage: metaOntology 1x im Constructor erzeugen und speichern oder Methode die Stream zurück gibt?
            metaOntology = ontologyManager.createOntology(rootOntology.axioms()
                    .filter(owlAxiom -> owlAxiom.isOfType(AxiomType.LOGICAL_AXIOM_TYPES))
                    .filter(owlAxiom -> owlAxiom.annotations(isDefinedBy).count() == 0)
                    .filter(owlAxiom -> owlAxiom.annotations(label)
                            .filter(owlAnnotation -> owlAnnotation.getValue().equals(objectGlobal))
                            .count() == 0)
                    .collect(Collectors.toSet()));
            //TODO doch keine metaOntologyIRI, falls man von einer rootOntology mehrere ContextOntologies erzeugt,
            // erzeugt man hier einen Fehler, da die metaOntology anscheinend schon existiert
            //IRI.create(rootOntology.getOntologyID().getOntologyIRI().orElse(IRI.create("")) + "_meta"));

        } catch (OWLOntologyCreationException e) {
            e.printStackTrace();
            throw new ContextOntologyException(
                    "\nCould not construct meta ontology.");
        }
    }

    /**
     * This method adds repletion axioms (e.g. ⊤ ⊑ A_α ⊔ A_¬α for all A_α) to the meta ontology. It is important that
     * this method is called after the initialisation of the objectAxiomMap since it uses its key set.
     */
    private void addRepletionAxiomsToMetaOntology() {

        // Step 1: Add repletion  axioms to meta ontology
        // ⊤ ⊑ α ⊔ α*
        ontologyManager.addAxioms(metaOntology,
                objectAxiomsMap.keySet().stream().map(owlClass ->
                        dataFactory.getOWLSubClassOfAxiom(
                                dataFactory.getOWLThing(),
                                dataFactory.getOWLObjectUnionOf(owlClass, getDualClass(owlClass)))));

        // α ⊓ α* ⊑ ⊥
        ontologyManager.addAxioms(metaOntology,
                objectAxiomsMap.keySet().stream().map(owlClass ->
                        dataFactory.getOWLSubClassOfAxiom(
                                dataFactory.getOWLObjectIntersectionOf(owlClass, getDualClass(owlClass)),
                                dataFactory.getOWLNothing())));

        // Step 2: Add negated Axioms to objectAxiomMap
        objectAxiomsMap.putAll(objectAxiomsMap.entrySet().stream()
                .map(owlClassOWLAxiomEntry -> owlClassOWLAxiomEntry)
                .collect(Collectors.toMap(
                        entry -> getDualClass(entry.getKey()),
                        entry -> entry.getValue().accept(new AxiomNegator(dataFactory)))));
    }

    /**
     * This method adds dual axioms (e.g. ¬A_¬α ⊑ C for A_α ⊑ C) to the meta ontology. It is important that this
     * method is called after the initialisation of the objectAxiomMap since it uses outerAbstractedMetaConcepts()
     */
    private void addDualAxiomsToMetaOntology() {

        // Step 1: Add negated Axioms to objectAxiomMap
        objectAxiomsMap.putAll(objectAxiomsMap.entrySet().stream()
                .map(owlClassOWLAxiomEntry -> owlClassOWLAxiomEntry)
                .collect(Collectors.toMap(
                        entry -> getDualClass(entry.getKey()),
                        entry -> entry.getValue().accept(new AxiomNegator(dataFactory)))));

        // Step 2: Add dual axioms to meta ontology
        ontologyManager.addAxioms(metaOntology, metaOntology.axioms()
                // TODO Does it do what I think it does???
                // only consider axioms that contain abstracted meta concepts
                .filter(owlAxiom -> owlAxiom.classesInSignature().anyMatch(classIsAbstractedMetaConcept))
                // map each axiom to a set of axioms, each constructed by replacing a subset of the abstracted concepts
                // with its dual concept.
                .flatMap(owlAxiom ->
                        Powerset.powerset(owlAxiom
                                .classesInSignature()
                                .filter(classIsAbstractedMetaConcept)
                                .collect(Collectors.toCollection(LinkedList::new)))
                                // filter out the empty set, since that doesn't change the axiom; and the original
                                // axiom is already in the meta ontology
                                .filter(set -> !set.isEmpty())
                                .map(conceptsToChange -> owlAxiom.accept(new AxiomToDual(dataFactory, conceptsToChange)))));

    }

    private OWLOntology asOntology(OWLAxiom owlAxiom) {

        try {
            return ontologyManager.createOntology(Stream.of(owlAxiom));
        } catch (OWLOntologyCreationException e) {
            e.printStackTrace();
            throw new ContextOntologyException(
                    "\nCould not construct ontology out of axiom.");
        }
    }

    /**
     * For an OWL class, create its "dual" class which is a new generated OWL class where the IRI has the
     * same namespace, but the remainder is prefixed with "DUAL.".
     *
     * @param owlClass input OWL class
     * @return the dual OWL class
     */
    private OWLClass getDualClass(OWLClass owlClass) {
        return dataFactory.getOWLClass(
                owlClass.getIRI().getNamespace(),
                "DUAL." + owlClass.getIRI().getRemainder().orElse(""));
    }

    public Stream<OWLEntity> metaSignature() {
        return this.getMetaOntology().signature();
    }

    public Stream<OWLClass> classesInMetaSignature() {
        return this.getMetaOntology().classesInSignature();
    }

    public Stream<OWLObjectProperty> objectPropertiesInMetaSignature() {
        return this.getMetaOntology().objectPropertiesInSignature();
    }

    public Stream<OWLEntity> objectSignature() {
        // TODO Wenn object names öfter in objectAxiomMap vorkommen, stehen sie auch öfters in objectSignature. Eigentlich doof, aber ist uns das egal?
        return Stream.concat(
                objectAxiomsMap.entrySet().stream()
                        .map(Map.Entry::getValue)
                        .flatMap(HasSignature::signature),
                globalObjectOntology().flatMap(HasSignature::signature)
        );
    }

    public Stream<OWLClass> classesInObjectSignature() {
        return objectSignature()
                .filter(entityIsOWLClass)
                .map(dataFactory::getOWLClass);
    }

    public Stream<OWLObjectProperty> objectPropertiesInObjectSignature() {
        return objectSignature()
                .filter(entityIsOWLObjectProperty)
                .map(dataFactory::getOWLObjectProperty);
    }


    // todo das ist 4 mal fast der gleiche Code. Kann man das nicht irgendwie abkürzen? Mit Collectors.groupingBy?
    public Stream<OWLClass> rigidClasses() {
        return rootOntology
                .signature()
                .map(HasIRI::getIRI)
                .filter(iriIsObjectLevel.and(iriIsRigid).and(iriIsOWLClass))
                .map(dataFactory::getOWLClass);
    }

    public Stream<OWLObjectProperty> rigidObjectProperties() {
        return rootOntology
                .signature()
                .map(HasIRI::getIRI)
                .filter(iriIsObjectLevel.and(iriIsRigid).and(iriIsOWLObjectProperty))
                .map(dataFactory::getOWLObjectProperty);
    }

    public Stream<OWLClass> flexibleClasses() {
        return rootOntology
                .signature()
                .map(HasIRI::getIRI)
                .filter(iriIsObjectLevel.and(iriIsRigid.negate()).and(iriIsOWLClass))
                .map(dataFactory::getOWLClass);
    }

    public Stream<OWLObjectProperty> flexibleObjectProperties() {
        return rootOntology
                .signature()
                .map(HasIRI::getIRI)
                .filter(iriIsObjectLevel.and(iriIsRigid.negate()).and(iriIsOWLObjectProperty))
                .map(dataFactory::getOWLObjectProperty);
    }

    public Stream<OWLEntity> flexibleNames() {

        return Stream.of(flexibleClasses(), flexibleObjectProperties()).flatMap(Function.identity());
    }

    /**
     * This method checks whether the context ontology contains rigid names.
     *
     * @return true if the context ontology contains rigid names.
     */
    public boolean containsRigidNames() {
        return containsRigidNames;
    }


    /**
     * This method retrieves all meta concepts that identify object axioms
     *
     * @return Stream of OWL classes that identify object axioms.
     */
    public Stream<OWLClass> outerAbstractedMetaConcepts() {
        if (objectAxiomsMap == null)
            throw new ContextOntologyException(
                    "\nouterAbstractedMetaConcepts() cannot be called until objectAxiomMap is initialized.");
        return objectAxiomsMap.keySet().stream();
    }

    /**
     * This method obtains all global object axioms.
     *
     * @return Stream of OWL axioms that must hold in every context.
     */
    public Stream<OWLAxiom> globalObjectOntology() {
        // TODO sehr gefährlich, rootOntology wird nicht in der contextOntology gespeichert, wenn später darauf-
        // zugegriffen wird, könnte sie von außen schon geändert wurden sein.
        return rootOntology.axioms()
                // consider only logical axioms
                .filter(owlAxiom -> owlAxiom.isOfType(AxiomType.LOGICAL_AXIOM_TYPES))
                // consider only axioms that are labelled with 'global'
                .filter(owlAxiom -> owlAxiom.annotations(label)
                        .filter(owlAnnotation -> owlAnnotation.getValue().equals(objectGlobal))
                        .count() > 0)
                .map(owlAxiom -> (OWLAxiom) owlAxiom.getAxiomWithoutAnnotations());
    }

    /**
     * This method returns the meta ontology.
     *
     * @return The meta ontology.
     */
    public OWLOntology getMetaOntology() {

        return metaOntology;
    }

    /**
     * This method returns an object ontology associated to the given sets of meta classes.
     *
     * @param positiveMetaClasses A set of meta classes whose axioms must hold in the object ontology.
     * @param negativeMetaClasses A set of meta classes whose axioms must not hold in the object ontology.
     * @return The associated object ontology.
     */
    public OWLOntology getObjectOntology(Stream<OWLClass> positiveMetaClasses, Stream<OWLClass> negativeMetaClasses) {

        try {

            //create the object ontology
            return ontologyManager.createOntology(Stream.of(
                    globalObjectOntology(),
                    positiveMetaClasses
                            .filter(classIsAbstractedMetaConcept)
                            .map(objectAxiomsMap::get),
                    negativeMetaClasses
                            .filter(classIsAbstractedMetaConcept)
                            .map(objectAxiomsMap::get)
                            .map(axiom -> axiom.accept(new AxiomNegator(dataFactory))))
                    .flatMap(Function.identity()));

        } catch (OWLOntologyCreationException e) {

            e.printStackTrace();
        }

        return null;
    }


    /**
     * This method returns an object ontology associated to the given set of restrictedTypes. If the context
     * ontology does not contain any rigid names, each world is checked separately. Hence, the
     * object ontology is only needed for a single type. If rigid names are present, a combined
     * object ontology for all restrictedTypes based on the renaming technique is returned.
     *
     * @param restrictedTypes A set restrictedTypes for which the object ontology should be constructed.
     * @return The associated object ontology.
     */
    public OWLOntology getObjectOntology(List<RestrictedType> restrictedTypes) {

        if (containsRigidNames()) {

            try {
                OWLOntology objectOntology = ontologyManager.createOntology();

//                IntStream.range(0, restrictedTypes.size()).forEach(idx -> {
//                    OWLOntology ontologyToBeRenamed = getObjectOntologyForSingleType(restrictedTypes.get(idx));
//                    AxiomRenamer renamer = new AxiomRenamer(ontologyToBeRenamed);
//                    renamer.rename(flexibleNames().collect(Collectors.toSet()), idx);
//                    objectOntology.addAxioms(ontologyToBeRenamed.axioms().collect(Collectors.toSet()));
//                });

                // TODO for-loop only for easier debugging, renaming takes ages :(
                for (int idx = 0; idx < restrictedTypes.size(); idx++) {
                    OWLOntology ontologyToBeRenamed = getObjectOntologyForSingleType(restrictedTypes.get(idx));
                    AxiomRenamer renamer = new AxiomRenamer(ontologyToBeRenamed);
                    renamer.rename(flexibleNames().collect(Collectors.toSet()), idx);
                    objectOntology.addAxioms(ontologyToBeRenamed.axioms().collect(Collectors.toSet()));
                }

                return objectOntology;
            } catch (OWLOntologyCreationException e) {

                e.printStackTrace();
            }
            throw new RuntimeException("rigid names not implemented yet!");
        } else {
            // no rigid names

            if (restrictedTypes.size() != 1) {
                throw new ContextOntologyException("If no rigid names are present, getObjectOntology() can only " +
                        "be called for a single type!");
            }

            OWLOntology objectOntology = getObjectOntologyForSingleType(restrictedTypes.stream().findAny().get());

            if (configuration.debugOutput()>0) {
                System.out.print("Axioms in object ontology: ");
                System.out.println(objectOntology != null ? objectOntology.axioms().count() : 0);
                System.out.println();
            }

            return objectOntology;
        }
    }

    private OWLOntology getObjectOntologyForSingleType(RestrictedType restrictedType) {

        try {
            return ontologyManager.createOntology(Stream.of(
                    globalObjectOntology(),
                    restrictedType.positiveConcepts()
                            .filter(classIsAbstractedMetaConcept)
                            .map(objectAxiomsMap::get),
                    restrictedType.negativeConcepts()
                            .filter(classIsAbstractedMetaConcept)
                            .map(objectAxiomsMap::get)
                            .map(axiom -> axiom.accept(new AxiomNegator(dataFactory))))
                    .flatMap(Function.identity()));

        } catch (OWLOntologyCreationException e) {

            e.printStackTrace();
        }

        return null;
    }

    /**
     * @return The OWL data factory of the ontology manager that was used to create the context.
     */
    public OWLDataFactory getDataFactory() {

        return ontologyManager.getOWLDataFactory();
    }

    public Configuration getConfiguration() {

        return configuration;
    }

    public String getStatistics() {

        StringBuilder builder = new StringBuilder();

        // Meta Ontology
        builder.append("Meta Ontology IRI: ");
        builder.append(metaOntology.getOntologyID().getOntologyIRI()
                .map(IRI::toString)
                .orElse("no IRI specified"));
        builder.append("\n");
        builder.append("Meta axioms: ");
        builder.append(metaOntology.axioms().count());
        builder.append("\n");
        builder.append("Meta concepts / roles / individuals: ");
        builder.append(metaOntology.classesInSignature().count());
        builder.append(" / ");
        builder.append(metaOntology.objectPropertiesInSignature().count());
        builder.append(" / ");
        builder.append(metaOntology.individualsInSignature().count());
        builder.append("\n");
        builder.append("Outer abstracted meta concepts: ");
        builder.append(objectAxiomsMap.keySet().size());
        builder.append("\n\n");

        // Global Object Ontology
        builder.append("Object concepts / roles / individuals in global object ontology: ");
        builder.append(globalObjectOntology().flatMap(HasClassesInSignature::classesInSignature)
                .collect(Collectors.toSet()).size());
        builder.append(" / ");
        builder.append(globalObjectOntology().flatMap(HasObjectPropertiesInSignature::objectPropertiesInSignature)
                .collect(Collectors.toSet()).size());
        builder.append(" / ");
        builder.append(globalObjectOntology().flatMap(HasIndividualsInSignature::individualsInSignature)
                .collect(Collectors.toSet()).size());
        builder.append('\n');
        builder.append("Axioms in global object ontology: ");
        builder.append(globalObjectOntology().count());


        return builder.toString();
    }

    @Override
    public String toString() {

        StringBuilder builder = new StringBuilder();

        // Obtain meta ontology’s IRI.
        builder.append("Meta Ontology IRI: ");
        builder.append(metaOntology.getOntologyID().getOntologyIRI()
                .map(IRI::toString)
                .orElse("no IRI specified"));
        builder.append("\n\n");

        // Obtain meta concepts that abbreviate object axioms.
        builder.append("Classes in meta signature: ");
        builder.append(classesInMetaSignature().count() + "\n");
        builder.append(classesInMetaSignature().limit(5)
                .map(OWLClass::toString)
                .collect(Collectors.joining("\n")));
        builder.append(classesInMetaSignature().count() > 5 ? "\n...\n\n" : "\n\n");
        builder.append("Meta concepts that identify object axioms: ");
        builder.append(outerAbstractedMetaConcepts().count() + "\n");
        builder.append(outerAbstractedMetaConcepts().limit(5)
                .map(OWLClass::toString)
                .collect(Collectors.joining("\n")));
        builder.append(outerAbstractedMetaConcepts().count() > 5 ? "\n...\n\n" : "\n\n");

        // Obtain meta ontology.
        builder.append("Meta Ontology: ");
        builder.append(metaOntology.axioms().count() + " axioms.\n");
        builder.append(metaOntology.axioms().limit(10)
                .sorted()
                .map(OWLAxiom::toString)
                .collect(Collectors.joining("\n")));
        builder.append(metaOntology.axioms().count() > 10 ? "\n...\n\n" : "\n\n");

        // Obtain object-axioms map.
        builder.append("Object-axioms map: ");
        builder.append(objectAxiomsMap.size() + " entries.\n");
        builder.append(objectAxiomsMap.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .limit(10)
                .flatMap(entry -> Stream.of(entry.getKey().toString(), " -> ", entry.getValue().toString(), "\n"))
                .collect(Collectors.joining()));
        builder.append(objectAxiomsMap.size() > 10 ? "...\n\n" : "\n");


        // Obtain global object-axioms.
        builder.append("Global object-axioms: ");
        builder.append(globalObjectOntology().count() + "\n");
        builder.append(globalObjectOntology()
                .sorted()
                .limit(10)
                .map(OWLAxiom::toString)
                .collect(Collectors.joining("\n")));
        builder.append(globalObjectOntology().count() > 10 ? "...\n\n" : "\n");

        // Are there rigid names?
        builder.append("Rigid Names: ");
        builder.append(containsRigidNames());
        builder.append("\nRigid Concept Names: ");
        builder.append(rigidClasses().count() + "\n");
        builder.append(rigidClasses()
                .sorted()
                .limit(10)
                .map(OWLClass::toString)
                .collect(Collectors.joining("\n")));
        builder.append(rigidClasses().count() > 10 ? "...\n\n" : "\n\n");
        builder.append("Rigid Role Names: ");
        builder.append(rigidObjectProperties().count() + "\n");
        builder.append(rigidObjectProperties()
                .sorted()
                .limit(10)
                .map(OWLObjectProperty::toString)
                .collect(Collectors.joining("\n")));
        builder.append(rigidObjectProperties().count() > 10 ? "...\n" : "\n");

        return builder.toString();
    }
}
