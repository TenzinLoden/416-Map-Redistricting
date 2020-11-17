package com.giants;

import com.giants.domain.*;
import com.giants.enums.Ethnicity;
import com.giants.enums.StateAbbreviation;
import com.giants.enums.JobStatus;

import javax.persistence.EntityManager;
import javax.persistence.Query;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class JobHandler {

    public Job createJob(StateAbbreviation stateName, int userCompactness, double populationDifferenceLimit, List<Ethnicity> ethnicities, int numberOfMaps) {
        Job job = new Job(stateName, userCompactness, populationDifferenceLimit, numberOfMaps, ethnicities);
        if (numberOfMaps > 500) {
            job.setStatus(JobStatus.WAITING);
            int seaWulfId = job.executeSeaWulfJob();
            if (seaWulfId == -1) {
                // Error sending job to seawulf
            }
            job.setOnSeaWulf(seaWulfId);
        } else {
            job.setOnSeaWulf(-1);
            job.setStatus(JobStatus.RUNNING);
            job.executeLocalJob();
        }

        // Now add job to database
        EntityManager em = JPAUtility.getEntityManager();
//        EntityTransaction txn = em.getTransaction();
//        if (txn.isActive()) {
//            try {
//                txn.rollback();
//            } catch (PersistenceException | IllegalStateException e) {
//                System.out.println(e.getMessage());
//            }
//        }
        try {
            em.getTransaction().begin();
            em.persist(job);
            em.getTransaction().commit();
        } catch (Exception e) {
            // Return some kind of error here
            System.out.println(e.getMessage());
        } finally {
//            em.close();
        }
        return job;
    }

    public boolean cancelJobData(int jobId) {
        // Submit slurm to SeaWulf to cancel
        // For now pretend isCancelled is returned from seawulf (if running then return false)
        boolean isCancelled = true;

        // Based on isCancelled, use entity manager to update job info in db
        if (isCancelled) {
            EntityManager em = JPAUtility.getEntityManager();
            try {
                // Start of transaction
                em.getTransaction().begin();
                // Change database job status to cancelled
                Query q = em.createQuery("UPDATE Job SET status = :status WHERE id = :id");
                q.setParameter("id", jobId);
                q.setParameter("status", JobStatus.CANCELLED);
                q.executeUpdate();
                em.getTransaction().commit();
            } catch (Exception e) {
                // Return some kind of error here
                System.out.println(e.getMessage());
                return false;
            } finally {
//                em.close();
                // End of transaction
            }
        }

        return isCancelled;
    }

    public boolean deleteJobData(int jobId) {
        EntityManager em = JPAUtility.getEntityManager();
        try {
            // Start of transaction
            em.getTransaction().begin();
            // Delete job tuple from database
            Query q = em.createQuery("DELETE Job WHERE id = :id");
            q.setParameter("id", jobId);
            q.executeUpdate();
            em.getTransaction().commit();
        } catch (Exception e) {
            // Return some kind of error here
            return false;
        } finally {
//            em.close();
            // End of transaction
        }

        return true;
    }

    public List<Job> loadAllJobData() {
        List<Job> jobs = new ArrayList<Job>();

        // Get jobs from entityManager
        EntityManager em = JPAUtility.getEntityManager();
        try {
            // Get the list of all tuples in job table
//            Query q = em.createQuery("SELECT j FROM Jobs j", Job.class);
//            jobs = q.getResultList();
            jobs = em.createNamedQuery("Jobs.getJobs", Job.class).getResultList();
        } catch (Exception e) {
            // Return some kind of error here
            System.out.println(e.getMessage());
        }
        return jobs;
    }

    public String loadDistrictingData(int stateId) {
        List<District> districts = new ArrayList<District>();

        // Get State from entityManager
        EntityManager em = JPAUtility.getEntityManager();
        try {
            // Start of transaction
            em.getTransaction().begin();
            // Get all District objects where stateId == stateId
            Query q = em.createQuery("SELECT d FROM Districts d WHERE stateId = :stateId", Job.class)
                    .setParameter("stateId", stateId);
            em.getTransaction().commit();
            districts = q.getResultList();
        } catch (Exception e) {
            // Return some kind of error here

        } finally {
//            em.close();
            // End of transaction
        }

        // Return
        String geoJson = "";
        for (District district : districts) {
            geoJson += district.getGeoJson();
        }
        return geoJson;
    }

    public String loadPrecinctData(StateAbbreviation stateAbbreviation) {
        List<Precinct> precincts = new ArrayList<Precinct>();

        // Get State from entityManager
//        EntityManager em = JPAUtility.getEntityManager();
        try {
            // Start of transaction
//            em.getTransaction().begin();
//            // Get all Precinct objects where StateAbbreviation == stateAbbreviation
//            Query q = em.createQuery("SELECT p FROM Precincts p WHERE stateAbbreviation = :stateAbbreviation", Job.class)
//                    .setParameter("stateAbbreviation", stateAbbreviation);
//            em.getTransaction().commit();
//            precincts = q.getResultList();
        } catch (Exception e) {
            // Return some kind of error here

        } finally {
//            em.close();
            // End of transaction
        }

        String geoJson = "";
        for (Precinct precinct : precincts) {
            geoJson += precinct.getGeoJson();
        }
        return geoJson;
    }

    public List<Job> getJobStatusSeaWulf(List<Job> jobs) {
        // For each job in jobs that is waiting or running check SeaWulf
        // Pretend status is returned from SeaWulf
        // Iterator to handle sync issues
        Iterator<Job> jobIterator = jobs.iterator();
        while(jobIterator.hasNext()) {
            Job job = jobIterator.next();
            // Send slurm to check current status
            String status = "RUNNING";
            if (status.equals(JobStatus.COMPLETED.toString())) {
                // Send slurm script to calculate data (avg, extreme, boxwhiskers
                job.setStatus(JobStatus.COMPLETED);
                String filePath = job.retrieveSeaWulfData();
                // Methods below here return boolean not sure if check is necessary
                job.countCounties(filePath);
                job.generateJsonFile(filePath);
                job.generateAvgExtDistrictingPlan(filePath);
                job.generateBoxWhiskers(filePath);

                // Update the database
                EntityManager em = JPAUtility.getEntityManager();
                try {
                    // Start of transaction
                    em.getTransaction().begin();
                    // Change database job status to cancelled
                    Query q = em.createQuery("UPDATE Jobs SET status = :status, averageStateId = :averageStateId," +
                            "extremeStateId = :extremeStateId WHERE id = :id");
                    q.setParameter("id", job.getId());
                    q.setParameter("averageStateId", job.getAverageState());
                    q.setParameter("extremeStateId", job.getExtremeState());
                    q.setParameter("status", "cancelled");
                    q.executeUpdate();

                    // UPDATE BOX WHISKER TABLE HEREEE

                    em.getTransaction().commit();
                } catch (Exception e) {
                    // Return some kind of error here

                } finally {
//                        em.close();
                }
            } else if (!job.getStatus().equals(JobStatus.valueOf(status))) {
                job.setStatus(JobStatus.valueOf(status));
                // Merge in Entity Manager
            } else {
                // Only jobs with changed status will stay in list of jobs to return
                jobIterator.remove();
            }
        }

        return jobs;
    }
}
