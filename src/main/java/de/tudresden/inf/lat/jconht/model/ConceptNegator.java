package de.tudresden.inf.lat.jconht.model;

import org.semanticweb.owlapi.model.*;

/**
 * This class is used to generate the complement of a given OWLClassExpression.
 *
 * @author Stephan Böhme
 * @author Marcel Lippmann
 */
public class ConceptNegator implements OWLClassExpressionVisitorEx<OWLClassExpression> {

    private OWLDataFactory dataFactory;

    /**
     * This is the standard constructor.
     *
     * @param dataFactory the OWL data factory
     */
    public ConceptNegator(OWLDataFactory dataFactory) {

        this.dataFactory = dataFactory;
    }

    @Override
    public <T> OWLClassExpression doDefault(T object) {

        try {

            return ((OWLClassExpression) object).getObjectComplementOf();

        } catch (ClassCastException e) {

            // This should never happen!
            throw new UnhandledClassExpressionException("Unhandled class expression in ConceptNegator: " + object.getClass());
        }
    }

    @Override
    public OWLClassExpression visit(OWLObjectComplementOf owlObjectComplementOf) {

        return owlObjectComplementOf.getOperand();
    }

    @Override
    public OWLClassExpression visit(OWLClass owlClass) {

        if (owlClass.isOWLThing()) {
            return dataFactory.getOWLNothing();
        }

        if (owlClass.isOWLNothing()) {
            return dataFactory.getOWLThing();
        }

        return owlClass.getObjectComplementOf();
    }

}
