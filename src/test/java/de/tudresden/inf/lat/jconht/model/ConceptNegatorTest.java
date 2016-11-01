package de.tudresden.inf.lat.jconht.model;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.semanticweb.HermiT.ReasonerFactory;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.*;
import org.semanticweb.owlapi.reasoner.OWLReasoner;

import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * This is a test class for <code>ConceptNegator</code>.
 *
 * @author Stephan Böhme
 * @author Marcel Lippmann
 */
public class ConceptNegatorTest {

    private OWLOntologyManager manager;
    private OWLDataFactory dataFactory;
    private ReasonerFactory reasonerFactory;
    private ConceptNegator conceptNegator;

    private OWLClass owlThing;
    private OWLClass owlNothing;
    private OWLClass clsA;
    private OWLClass clsB;

    private OWLObjectComplementOf complementOfA;

    private OWLObjectIntersectionOf intersectionOfAAndB;

    @Before
    public void setUp() throws Exception {

        manager = OWLManager.createOWLOntologyManager();
        dataFactory = manager.getOWLDataFactory();
        reasonerFactory = new ReasonerFactory();
        conceptNegator = new ConceptNegator(dataFactory);

        owlThing = dataFactory.getOWLThing();
        owlNothing = dataFactory.getOWLNothing();
        clsA = dataFactory.getOWLClass("cls:A");
        clsB = dataFactory.getOWLClass("cls:B");

        complementOfA = dataFactory.getOWLObjectComplementOf(clsA);
        intersectionOfAAndB = dataFactory.getOWLObjectIntersectionOf(clsA, clsB);
    }

    @After
    public void tearDown() throws Exception {

        dataFactory.purge();
    }

    @Test
    public void testOWLThing() throws Exception {

        // pure syntactical test
        assertEquals("Negation of OWLThing is OWLNothing",
                owlNothing,
                owlThing.accept(conceptNegator));
    }

    @Test
    public void testOWLNothing() throws Exception {

        // pure syntactical test
        assertEquals("Negation of OWLNothing is OWLThing",
                owlThing,
                owlNothing.accept(conceptNegator));
    }

    @Test
    public void testOWLObjectComplementOf() throws Exception {

        // pure syntactical test
        assertEquals("Negation of OWLObjectComplementOf",
                clsA,
                complementOfA.accept(conceptNegator));
    }

    @Test
    public void testOWLClass() throws Exception {

        // D is negation of C iff ⊭(C ⊓ D)(x) and ⊨(C ⊔ D)(x)
        //⊨

        assertTrue("Negation of OWLClass: (C ⊓ D)(x) inconsistent",
                IsInconsistent(Stream.of(dataFactory.getOWLClassAssertionAxiom(
                        dataFactory.getOWLObjectIntersectionOf(clsA, clsA.accept(conceptNegator)),
                        dataFactory.getOWLAnonymousIndividual()))));

        assertTrue("Negation of OWLClass: ⊨ (C ⊔ D)(x)",
                EmptyOntologyEntails(dataFactory.getOWLClassAssertionAxiom(
                        dataFactory.getOWLObjectUnionOf(clsA, clsA.accept(conceptNegator)),
                        dataFactory.getOWLAnonymousIndividual())));

    }

    @Test
    public void testOWLObjectIntersectionOf() throws Exception {

        OWLReasoner reasoner = reasonerFactory.createReasoner(
                manager.createOntology(Stream.of(
                        dataFactory.getOWLDisjointUnionAxiom(
                                dataFactory.getOWLThing(),
                                Stream.of(
                                        intersectionOfAAndB,
                                        intersectionOfAAndB.accept(conceptNegator).getNNF())))));

        assertTrue("Negation of OWLObjectIntersectionOf", reasoner.isConsistent());

        reasoner.dispose();
    }

    private boolean IsInconsistent(Stream<OWLAxiom> axioms) throws OWLOntologyCreationException {

        OWLReasoner reasoner = reasonerFactory.createReasoner(
                manager.createOntology(axioms));
        return !reasoner.isConsistent();
    }

    private boolean EmptyOntologyEntails(OWLAxiom axiom) throws OWLOntologyCreationException {
        OWLReasoner reasoner = reasonerFactory.createReasoner(
                manager.createOntology());
        return reasoner.isEntailed(axiom);
    }
}